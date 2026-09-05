/*
 * Collect the kernel's post-resolution major/minor page-fault software events.
 *
 * Android's arm64 kernels don't expose the x86 page_fault_user tracepoint.
 * PERF_COUNT_SW_PAGE_FAULTS_{MIN,MAJ}, however, are emitted after
 * handle_mm_fault() on both architectures and carry the faulting virtual
 * address in perf's sample address field.
 */

#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <linux/perf_event.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

#include "cpu_list.h"
#include "apk_cache_reclaim.h"

#define RING_DATA_PAGES 256
#define MAX_SAMPLES 500000
#define MAX_MAPPING_SAMPLES 100000
#define MAX_MAPPING_PATH 512
#define MAX_CALLCHAIN_ENTRIES 4000000
#define PERF_RECORD_LOST_SAMPLES_TYPE 13
#define PERF_FORMAT_LOST_FLAG (1ULL << 4)

enum fault_kind {
  FAULT_MINOR = 0,
  FAULT_MAJOR = 1,
};

struct fault_sample {
  uint64_t timestamp_ns;
  uint64_t ip;
  uint64_t address;
  uint64_t callchain_offset;
  uint32_t pid;
  uint32_t tid;
  uint32_t cpu;
  uint32_t callchain_count;
  uint8_t kind;
};

struct mapping_sample {
  uint64_t timestamp_ns;
  uint64_t address;
  uint64_t length;
  uint64_t file_offset;
  uint64_t inode;
  uint32_t pid;
  uint32_t tid;
  uint32_t device_major;
  uint32_t device_minor;
  uint32_t protection;
  uint32_t flags;
  char file_name[MAX_MAPPING_PATH];
};

struct perf_ring {
  int fd;
  enum fault_kind kind;
  struct perf_event_mmap_page *metadata;
  size_t mmap_size;
  size_t data_size;
  uint64_t lost;
  uint64_t counter_lost;
};

static volatile sig_atomic_t stop_requested;

static void handle_signal(int signal_number) {
  (void)signal_number;
  stop_requested = 1;
}

static uint64_t capture_time_ns(void) {
  struct timespec timestamp;
  if (clock_gettime(CLOCK_BOOTTIME, &timestamp) != 0) {
    perror("clock_gettime");
    exit(EXIT_FAILURE);
  }
  return (uint64_t)timestamp.tv_sec * 1000000000ULL +
         (uint64_t)timestamp.tv_nsec;
}

static int perf_event_open(struct perf_event_attr *attr, pid_t pid, int cpu) {
  return (int)syscall(__NR_perf_event_open, attr, pid, cpu, -1, 0);
}

static void copy_from_ring(const struct perf_ring *ring, uint64_t offset,
                           void *destination, size_t size) {
  const uint8_t *data = (const uint8_t *)ring->metadata + getpagesize();
  const size_t begin = (size_t)(offset % ring->data_size);
  const size_t first_size =
      size < ring->data_size - begin ? size : ring->data_size - begin;

  memcpy(destination, data + begin, first_size);
  if (first_size < size) {
    memcpy((uint8_t *)destination + first_size, data, size - first_size);
  }
}

static void drain_ring(struct perf_ring *ring, struct fault_sample *samples,
                       size_t *sample_count, struct mapping_sample *mappings,
                       size_t *mapping_count, uint64_t *discarded,
                       uint64_t *integrity_errors, uint64_t *throttled,
                       uint64_t *callchains, size_t *callchain_count,
                       uint64_t *callchain_overflow,
                       bool capture_callchains) {
  uint64_t tail = ring->metadata->data_tail;
  const uint64_t head =
      __atomic_load_n(&ring->metadata->data_head, __ATOMIC_ACQUIRE);

  while (tail < head) {
    struct perf_event_header header;
    copy_from_ring(ring, tail, &header, sizeof(header));

    if (header.size < sizeof(header) || header.size > ring->data_size) {
      fprintf(stderr, "Invalid perf record size: %u\n", header.size);
      *integrity_errors += 1;
      stop_requested = 1;
      break;
    }

    if (header.type == PERF_RECORD_SAMPLE) {
      struct {
        uint64_t ip;
        uint32_t pid;
        uint32_t tid;
        uint64_t time;
        uint64_t address;
        uint32_t cpu;
        uint32_t reserved;
        uint64_t period;
      } payload;

      const size_t expected_size = sizeof(header) + sizeof(payload);
      if (header.size >= expected_size) {
        copy_from_ring(ring, tail + sizeof(header), &payload, sizeof(payload));
        uint64_t callchain_offset = 0;
        uint32_t captured_callchain_count = 0;
        if (capture_callchains) {
          uint64_t entry_count = 0;
          const size_t entry_count_offset = expected_size;
          if (header.size < entry_count_offset + sizeof(entry_count)) {
            *integrity_errors += 1;
            stop_requested = 1;
            tail += header.size;
            continue;
          }
          copy_from_ring(ring, tail + entry_count_offset, &entry_count,
                         sizeof(entry_count));
          const size_t available_entries =
              (header.size - entry_count_offset - sizeof(entry_count)) /
              sizeof(uint64_t);
          if (entry_count > available_entries || entry_count > UINT32_MAX) {
            fprintf(stderr,
                    "Invalid callchain count: %" PRIu64
                    " entries, %zu available\n",
                    entry_count, available_entries);
            *integrity_errors += 1;
            stop_requested = 1;
            tail += header.size;
            continue;
          }
          if (entry_count > MAX_CALLCHAIN_ENTRIES - *callchain_count) {
            *callchain_overflow += 1;
            stop_requested = 1;
            tail += header.size;
            continue;
          }
          callchain_offset = *callchain_count;
          captured_callchain_count = (uint32_t)entry_count;
          if (entry_count > 0) {
            copy_from_ring(ring,
                           tail + entry_count_offset + sizeof(entry_count),
                           callchains + *callchain_count,
                           (size_t)entry_count * sizeof(uint64_t));
            *callchain_count += (size_t)entry_count;
          }
        }
        if (*sample_count < MAX_SAMPLES) {
          samples[*sample_count] = (struct fault_sample){
              .timestamp_ns = payload.time,
              .ip = payload.ip,
              .address = payload.address,
              .callchain_offset = callchain_offset,
              .pid = payload.pid,
              .tid = payload.tid,
              .cpu = payload.cpu,
              .callchain_count = captured_callchain_count,
              .kind = (uint8_t)ring->kind,
          };
          *sample_count += 1;
        } else {
          *discarded += 1;
        }
      } else {
        *integrity_errors += 1;
        stop_requested = 1;
      }
    } else if (header.type == PERF_RECORD_MMAP2) {
      struct {
        uint32_t pid;
        uint32_t tid;
        uint64_t address;
        uint64_t length;
        uint64_t file_offset;
        uint32_t device_major;
        uint32_t device_minor;
        uint64_t inode;
        uint64_t inode_generation;
        uint32_t protection;
        uint32_t flags;
      } payload;
      struct {
        uint32_t pid;
        uint32_t tid;
        uint64_t time;
        uint32_t cpu;
        uint32_t reserved;
      } sample_id;
      const size_t fixed_size = sizeof(header) + sizeof(payload);
      const size_t sample_id_size = sizeof(sample_id);
      if (header.size < fixed_size + 1 + sample_id_size) {
        *integrity_errors += 1;
        stop_requested = 1;
      } else if (*mapping_count >= MAX_MAPPING_SAMPLES) {
        *discarded += 1;
      } else {
        copy_from_ring(ring, tail + sizeof(header), &payload, sizeof(payload));
        copy_from_ring(ring, tail + header.size - sample_id_size, &sample_id,
                       sample_id_size);
        const size_t available_path = header.size - fixed_size - sample_id_size;
        const size_t copy_size = available_path < MAX_MAPPING_PATH
                                     ? available_path
                                     : MAX_MAPPING_PATH - 1;
        struct mapping_sample *mapping = &mappings[*mapping_count];
        *mapping = (struct mapping_sample){
            .timestamp_ns = sample_id.time,
            .address = payload.address,
            .length = payload.length,
            .file_offset = payload.file_offset,
            .inode = payload.inode,
            .pid = payload.pid,
            .tid = payload.tid,
            .device_major = payload.device_major,
            .device_minor = payload.device_minor,
            .protection = payload.protection,
            .flags = payload.flags,
        };
        copy_from_ring(ring, tail + fixed_size, mapping->file_name, copy_size);
        mapping->file_name[MAX_MAPPING_PATH - 1] = '\0';
        if (memchr(mapping->file_name, '\0', copy_size) == NULL) {
          *integrity_errors += 1;
          stop_requested = 1;
        } else {
          *mapping_count += 1;
        }
      }
    } else if (header.type == PERF_RECORD_LOST) {
      struct {
        uint64_t id;
        uint64_t lost;
      } payload;
      if (header.size >= sizeof(header) + sizeof(payload)) {
        copy_from_ring(ring, tail + sizeof(header), &payload, sizeof(payload));
        ring->lost += payload.lost;
      }
    } else if (header.type == PERF_RECORD_LOST_SAMPLES_TYPE) {
      uint64_t lost;
      if (header.size >= sizeof(header) + sizeof(lost)) {
        copy_from_ring(ring, tail + sizeof(header), &lost, sizeof(lost));
        ring->lost += lost;
      } else {
        *integrity_errors += 1;
        stop_requested = 1;
      }
    } else if (header.type == PERF_RECORD_THROTTLE ||
               header.type == PERF_RECORD_UNTHROTTLE) {
      *throttled += 1;
      stop_requested = 1;
    }

    tail += header.size;
  }

  __atomic_store_n(&ring->metadata->data_tail, tail, __ATOMIC_RELEASE);
}

static int compare_samples(const void *left, const void *right) {
  const struct fault_sample *a = left;
  const struct fault_sample *b = right;
  if (a->timestamp_ns < b->timestamp_ns) {
    return -1;
  }
  if (a->timestamp_ns > b->timestamp_ns) {
    return 1;
  }
  return 0;
}

static void usage(const char *program) {
  fprintf(stderr,
          "Usage: %s --output FILE --mappings-output FILE "
          "[--callchains-output FILE] [--duration-ms N]\n"
          "       %s --residency FILE [FILE ...]\n"
          "       %s --evict FILE [FILE ...]\n"
          "       %s --reclaim-mapped-apks APK [APK ...]\n",
          program, program, program, program);
}

static int read_online_cpus(int **cpus_out, size_t *count_out,
                            char *online_text, size_t online_text_size) {
  const char *path = "/sys/devices/system/cpu/online";
  FILE *input = fopen(path, "r");
  if (input == NULL) {
    fprintf(stderr, "Unable to open %s: %s\n", path, strerror(errno));
    return -1;
  }
  if (fgets(online_text, (int)online_text_size, input) == NULL) {
    fprintf(stderr, "Unable to read %s: %s\n", path, strerror(errno));
    fclose(input);
    return -1;
  }
  if (fclose(input) != 0) {
    fprintf(stderr, "Unable to close %s: %s\n", path, strerror(errno));
    return -1;
  }
  char error[160] = {0};
  if (parse_cpu_list(online_text, cpus_out, count_out, error, sizeof(error)) !=
      0) {
    fprintf(stderr, "Unable to parse %s (%s): %s\n", path, online_text, error);
    return -1;
  }
  online_text[strcspn(online_text, "\r\n")] = '\0';
  return 0;
}

static void write_csv_string(FILE *output, const char *value) {
  fputc('"', output);
  for (const char *cursor = value; *cursor != '\0'; ++cursor) {
    if (*cursor == '"') {
      fputc('"', output);
    }
    fputc(*cursor, output);
  }
  fputc('"', output);
}

static int evict_files(int file_count, char **paths) {
  for (int index = 0; index < file_count; ++index) {
    const char *path = paths[index];
    const int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
      if (errno == ENOENT) {
        fprintf(stderr, "Skipping file that disappeared during eviction: %s\n",
                path);
        continue;
      }
      fprintf(stderr, "Unable to open %s: %s\n", path, strerror(errno));
      return EXIT_FAILURE;
    }
    const int result = posix_fadvise(fd, 0, 0, POSIX_FADV_DONTNEED);
    close(fd);
    if (result != 0) {
      fprintf(stderr, "Unable to evict %s: %s\n", path, strerror(result));
      return EXIT_FAILURE;
    }
  }
  return EXIT_SUCCESS;
}

static int report_residency(int file_count, char **paths) {
  const long page_size = sysconf(_SC_PAGESIZE);
  if (page_size <= 0) {
    fprintf(stderr, "Unable to determine page size\n");
    return EXIT_FAILURE;
  }

  printf("file_name,size_bytes,total_pages,resident_pages\n");
  for (int index = 0; index < file_count; ++index) {
    const char *path = paths[index];
    const int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
      if (errno == ENOENT) {
        fprintf(stderr,
                "Skipping file that disappeared during residency check: %s\n",
                path);
        continue;
      }
      fprintf(stderr, "Unable to open %s: %s\n", path, strerror(errno));
      return EXIT_FAILURE;
    }

    struct stat file_stat;
    if (fstat(fd, &file_stat) != 0) {
      fprintf(stderr, "Unable to stat %s: %s\n", path, strerror(errno));
      close(fd);
      return EXIT_FAILURE;
    }

    const size_t total_pages =
        ((size_t)file_stat.st_size + (size_t)page_size - 1) / (size_t)page_size;
    size_t resident_pages = 0;
    if (total_pages > 0) {
      const size_t mapping_size = total_pages * (size_t)page_size;
      void *mapping = mmap(NULL, mapping_size, PROT_NONE, MAP_SHARED, fd, 0);
      if (mapping == MAP_FAILED) {
        fprintf(stderr, "Unable to map %s: %s\n", path, strerror(errno));
        close(fd);
        return EXIT_FAILURE;
      }

      unsigned char *residency = calloc(total_pages, 1);
      if (residency == NULL) {
        fprintf(stderr, "Unable to allocate residency vector\n");
        munmap(mapping, mapping_size);
        close(fd);
        return EXIT_FAILURE;
      }
      if (mincore(mapping, mapping_size, residency) != 0) {
        fprintf(stderr, "Unable to query residency for %s: %s\n", path,
                strerror(errno));
        free(residency);
        munmap(mapping, mapping_size);
        close(fd);
        return EXIT_FAILURE;
      }
      for (size_t page = 0; page < total_pages; ++page) {
        resident_pages += (residency[page] & 1U) != 0;
      }
      free(residency);
      munmap(mapping, mapping_size);
    }

    write_csv_string(stdout, path);
    printf(",%" PRIu64 ",%zu,%zu\n", (uint64_t)file_stat.st_size, total_pages,
           resident_pages);
    close(fd);
  }
  return EXIT_SUCCESS;
}

int main(int argc, char **argv) {
  if (argc >= 3 && strcmp(argv[1], "--reclaim-mapped-apks") == 0) {
    return reclaim_mapped_apks(argc - 2, argv + 2);
  }
  if (argc >= 3 && strcmp(argv[1], "--residency") == 0) {
    return report_residency(argc - 2, argv + 2);
  }
  if (argc >= 3 && strcmp(argv[1], "--evict") == 0) {
    return evict_files(argc - 2, argv + 2);
  }

  const char *output_path = NULL;
  const char *mappings_output_path = NULL;
  const char *callchains_output_path = NULL;
  uint64_t duration_ms = 10000;

  for (int index = 1; index < argc; ++index) {
    if (strcmp(argv[index], "--output") == 0 && index + 1 < argc) {
      output_path = argv[++index];
    } else if (strcmp(argv[index], "--mappings-output") == 0 &&
               index + 1 < argc) {
      mappings_output_path = argv[++index];
    } else if (strcmp(argv[index], "--callchains-output") == 0 &&
               index + 1 < argc) {
      callchains_output_path = argv[++index];
    } else if (strcmp(argv[index], "--duration-ms") == 0 && index + 1 < argc) {
      char *end = NULL;
      errno = 0;
      duration_ms = strtoull(argv[++index], &end, 10);
      if (errno != 0 || end == argv[index] || *end != '\0' ||
          duration_ms == 0) {
        usage(argv[0]);
        return EXIT_FAILURE;
      }
    } else {
      usage(argv[0]);
      return EXIT_FAILURE;
    }
  }

  if (output_path == NULL || mappings_output_path == NULL) {
    usage(argv[0]);
    return EXIT_FAILURE;
  }

  fprintf(stdout, "STARTING pid=%d\n", getpid());
  fflush(stdout);

  const long page_size = sysconf(_SC_PAGESIZE);
  int *online_cpus = NULL;
  size_t cpu_count = 0;
  char online_cpu_text[4096] = {0};
  if (page_size <= 0 ||
      read_online_cpus(&online_cpus, &cpu_count, online_cpu_text,
                       sizeof(online_cpu_text)) != 0) {
    fprintf(stderr, "Unable to determine online CPUs or page size\n");
    return EXIT_FAILURE;
  }

  const size_t ring_count = cpu_count * 2;
  struct perf_ring *rings = calloc(ring_count, sizeof(*rings));
  struct pollfd *poll_fds = calloc(ring_count, sizeof(*poll_fds));
  struct fault_sample *samples = malloc(MAX_SAMPLES * sizeof(*samples));
  struct mapping_sample *mappings =
      malloc(MAX_MAPPING_SAMPLES * sizeof(*mappings));
  uint64_t *callchains =
      callchains_output_path == NULL
          ? NULL
          : malloc(MAX_CALLCHAIN_ENTRIES * sizeof(*callchains));
  if (rings == NULL || poll_fds == NULL || samples == NULL ||
      mappings == NULL || (callchains_output_path != NULL && callchains == NULL)) {
    fprintf(stderr, "Unable to allocate collector buffers\n");
    return EXIT_FAILURE;
  }
  size_t opened_rings = 0;
  bool lost_counter_supported = true;
  for (size_t cpu_index = 0; cpu_index < cpu_count; ++cpu_index) {
    const int cpu = online_cpus[cpu_index];
    for (int kind = FAULT_MINOR; kind <= FAULT_MAJOR; ++kind) {
      struct perf_event_attr attr = {
          .type = PERF_TYPE_SOFTWARE,
          .size = sizeof(attr),
          .config = kind == FAULT_MAJOR ? PERF_COUNT_SW_PAGE_FAULTS_MAJ
                                        : PERF_COUNT_SW_PAGE_FAULTS_MIN,
          .sample_period = 1,
          .read_format =
              lost_counter_supported ? PERF_FORMAT_LOST_FLAG : 0,
          .sample_type = PERF_SAMPLE_IP | PERF_SAMPLE_TID | PERF_SAMPLE_TIME |
                         PERF_SAMPLE_ADDR | PERF_SAMPLE_CPU |
                         PERF_SAMPLE_PERIOD |
                         (callchains_output_path == NULL
                              ? 0
                              : PERF_SAMPLE_CALLCHAIN),
          .disabled = 1,
          .wakeup_events = 1,
          .use_clockid = 1,
          .clockid = CLOCK_BOOTTIME,
      };
      if (kind == FAULT_MINOR) {
        attr.mmap2 = 1;
        attr.mmap_data = 1;
        attr.sample_id_all = 1;
      }

      int fd = perf_event_open(&attr, -1, cpu);
      if (fd < 0 && errno == EINVAL && lost_counter_supported &&
          opened_rings == 0) {
        lost_counter_supported = false;
        attr.read_format = 0;
        fd = perf_event_open(&attr, -1, cpu);
      }
      if (fd < 0) {
        fprintf(stderr, "perf_event_open failed for cpu %d (%s): %s\n", cpu,
                kind == FAULT_MAJOR ? "major" : "minor", strerror(errno));
        return EXIT_FAILURE;
      }

      const size_t mmap_size =
          ((size_t)RING_DATA_PAGES + 1) * (size_t)page_size;
      void *mapping =
          mmap(NULL, mmap_size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
      if (mapping == MAP_FAILED) {
        fprintf(stderr, "perf ring mmap failed: %s\n", strerror(errno));
        return EXIT_FAILURE;
      }

      rings[opened_rings] = (struct perf_ring){
          .fd = fd,
          .kind = (enum fault_kind)kind,
          .metadata = mapping,
          .mmap_size = mmap_size,
          .data_size = (size_t)RING_DATA_PAGES * (size_t)page_size,
      };
      poll_fds[opened_rings] = (struct pollfd){
          .fd = fd,
          .events = POLLIN,
      };
      opened_rings += 1;
    }
  }

  signal(SIGINT, handle_signal);
  signal(SIGTERM, handle_signal);

  for (size_t index = 0; index < opened_rings; ++index) {
    if (ioctl(rings[index].fd, PERF_EVENT_IOC_RESET, 0) != 0 ||
        ioctl(rings[index].fd, PERF_EVENT_IOC_ENABLE, 0) != 0) {
      fprintf(stderr, "Unable to enable perf event: %s\n", strerror(errno));
      return EXIT_FAILURE;
    }
  }

  const uint64_t started_ns = capture_time_ns();
  const uint64_t deadline_ns = started_ns + duration_ms * 1000000ULL;
  size_t sample_count = 0;
  size_t mapping_count = 0;
  size_t callchain_count = 0;
  uint64_t discarded = 0;
  uint64_t integrity_errors = 0;
  uint64_t throttled = 0;
  uint64_t callchain_overflow = 0;

  fprintf(stderr,
          "READY pid=%d capture_start_ns=%" PRIu64 " online_cpus=%s\n",
          getpid(), started_ns, online_cpu_text);
  fflush(stderr);

  while (!stop_requested && capture_time_ns() < deadline_ns) {
    const int poll_result = poll(poll_fds, (nfds_t)opened_rings, 10);
    if (poll_result < 0 && errno != EINTR) {
      fprintf(stderr, "poll failed: %s\n", strerror(errno));
      integrity_errors += 1;
      break;
    }
    for (size_t index = 0; index < opened_rings; ++index) {
      drain_ring(&rings[index], samples, &sample_count, mappings,
                 &mapping_count, &discarded, &integrity_errors, &throttled,
                 callchains, &callchain_count, &callchain_overflow,
                 callchains_output_path != NULL);
    }
  }

  for (size_t index = 0; index < opened_rings; ++index) {
    ioctl(rings[index].fd, PERF_EVENT_IOC_DISABLE, 0);
    drain_ring(&rings[index], samples, &sample_count, mappings, &mapping_count,
               &discarded, &integrity_errors, &throttled, callchains,
               &callchain_count, &callchain_overflow,
               callchains_output_path != NULL);
    if (lost_counter_supported) {
      struct {
        uint64_t value;
        uint64_t lost;
      } result;
      const ssize_t bytes = read(rings[index].fd, &result, sizeof(result));
      if (bytes != (ssize_t)sizeof(result)) {
        fprintf(stderr,
                "Unable to read perf lost counter for ring %zu: %s\n", index,
                bytes < 0 ? strerror(errno) : "short read");
        integrity_errors += 1;
      } else {
        rings[index].counter_lost = result.lost;
      }
    }
  }
  const uint64_t ended_ns = capture_time_ns();

  qsort(samples, sample_count, sizeof(*samples), compare_samples);

  FILE *output = fopen(output_path, "w");
  if (output == NULL) {
    fprintf(stderr, "Unable to open %s: %s\n", output_path, strerror(errno));
    return EXIT_FAILURE;
  }
  fprintf(output, "timestamp_ns,event_type,pid,tid,ip,address,cpu\n");
  for (size_t index = 0; index < sample_count; ++index) {
    const struct fault_sample *sample = &samples[index];
    fprintf(output, "%" PRIu64 ",%s,%u,%u,0x%" PRIx64 ",0x%" PRIx64 ",%u\n",
            sample->timestamp_ns,
            sample->kind == FAULT_MAJOR ? "major" : "minor", sample->pid,
            sample->tid, sample->ip, sample->address, sample->cpu);
  }
  if (fclose(output) != 0) {
    fprintf(stderr, "Unable to close %s: %s\n", output_path, strerror(errno));
    return EXIT_FAILURE;
  }

  FILE *mappings_output = fopen(mappings_output_path, "w");
  if (mappings_output == NULL) {
    fprintf(stderr, "Unable to open %s: %s\n", mappings_output_path,
            strerror(errno));
    return EXIT_FAILURE;
  }
  fprintf(mappings_output,
          "timestamp_ns,pid,tid,address,length,file_offset,device_major,"
          "device_minor,inode,protection,flags,file_name\n");
  for (size_t index = 0; index < mapping_count; ++index) {
    const struct mapping_sample *mapping = &mappings[index];
    fprintf(mappings_output,
            "%" PRIu64 ",%u,%u,0x%" PRIx64 ",0x%" PRIx64 ",0x%" PRIx64
            ",%u,%u,%" PRIu64 ",%u,%u,",
            mapping->timestamp_ns, mapping->pid, mapping->tid, mapping->address,
            mapping->length, mapping->file_offset, mapping->device_major,
            mapping->device_minor, mapping->inode, mapping->protection,
            mapping->flags);
    write_csv_string(mappings_output, mapping->file_name);
    fputc('\n', mappings_output);
  }
  if (fclose(mappings_output) != 0) {
    fprintf(stderr, "Unable to close %s: %s\n", mappings_output_path,
            strerror(errno));
    return EXIT_FAILURE;
  }

  if (callchains_output_path != NULL) {
    FILE *callchains_output = fopen(callchains_output_path, "w");
    if (callchains_output == NULL) {
      fprintf(stderr, "Unable to open %s: %s\n", callchains_output_path,
              strerror(errno));
      return EXIT_FAILURE;
    }
    fprintf(callchains_output,
            "fault_index,timestamp_ns,event_type,pid,tid,address,"
            "frame_index,ip\n");
    for (size_t sample_index = 0; sample_index < sample_count; ++sample_index) {
      const struct fault_sample *sample = &samples[sample_index];
      /* An empty PERF_SAMPLE_CALLCHAIN is valid (e.g. recursion protection).
       * Keep an explicit sentinel so absence isn't confused with a lost row. */
      if (sample->callchain_count == 0) {
        fprintf(callchains_output,
                "%zu,%" PRIu64 ",%s,%u,%u,0x%" PRIx64 ",-1,0x0\n",
                sample_index, sample->timestamp_ns,
                sample->kind == FAULT_MAJOR ? "major" : "minor", sample->pid,
                sample->tid, sample->address);
      }
      for (uint32_t frame_index = 0;
           frame_index < sample->callchain_count; ++frame_index) {
        const uint64_t callchain_ip =
            callchains[sample->callchain_offset + frame_index];
        fprintf(callchains_output,
                "%zu,%" PRIu64 ",%s,%u,%u,0x%" PRIx64 ",%u,0x%" PRIx64
                "\n",
                sample_index, sample->timestamp_ns,
                sample->kind == FAULT_MAJOR ? "major" : "minor", sample->pid,
                sample->tid, sample->address, frame_index, callchain_ip);
      }
    }
    if (fclose(callchains_output) != 0) {
      fprintf(stderr, "Unable to close %s: %s\n", callchains_output_path,
              strerror(errno));
      return EXIT_FAILURE;
    }
  }

  uint64_t lost = discarded;
  for (size_t index = 0; index < opened_rings; ++index) {
    lost += rings[index].lost > rings[index].counter_lost
                ? rings[index].lost
                : rings[index].counter_lost;
    munmap(rings[index].metadata, rings[index].mmap_size);
    close(rings[index].fd);
  }

  fprintf(stderr,
          "capture_start_ns=%" PRIu64 " capture_end_ns=%" PRIu64
          " samples=%zu mappings=%zu lost=%" PRIu64 " integrity_errors=%" PRIu64
          " throttled=%" PRIu64 " callchain_entries=%zu"
          " callchain_overflow=%" PRIu64 " lost_counter_supported=%d"
          " max_samples=%d max_mappings=%d max_callchain_entries=%d"
          " record_buffer_bytes=%zu perf_ring_bytes=%zu\n",
          started_ns, ended_ns, sample_count, mapping_count, lost,
          integrity_errors, throttled, callchain_count, callchain_overflow,
          lost_counter_supported ? 1 : 0, MAX_SAMPLES, MAX_MAPPING_SAMPLES,
          callchains_output_path == NULL ? 0 : MAX_CALLCHAIN_ENTRIES,
          MAX_SAMPLES * sizeof(*samples) +
              MAX_MAPPING_SAMPLES * sizeof(*mappings) +
              (callchains_output_path == NULL
                   ? 0
                   : MAX_CALLCHAIN_ENTRIES * sizeof(*callchains)),
          opened_rings * ((size_t)RING_DATA_PAGES + 1) * (size_t)page_size);

  free(callchains);
  free(mappings);
  free(samples);
  free(poll_fds);
  free(rings);
  free(online_cpus);
  return lost == 0 && integrity_errors == 0 && throttled == 0 &&
                 callchain_overflow == 0
             ? EXIT_SUCCESS
             : 2;
}
