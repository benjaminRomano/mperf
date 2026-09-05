/* Optional pre-capture reclaim of exact APK read-only mappings. Linux may skip
 * shared/pinned pages even when advice succeeds. Always verify with mincore.
 * No process is stopped, no mapping is removed, and file contents are
 * unchanged.
 */
#ifndef APK_CACHE_RECLAIM_H
#define APK_CACHE_RECLAIM_H

#include <dirent.h>
#include <limits.h>
#include <sys/sysmacros.h>
#include <sys/uio.h>

#define APK_RECLAIM_MAX_FILES 128
#define APK_RECLAIM_MAX_RANGES 256
#define APK_RECLAIM_MAX_BYTES (1024ULL * 1024 * 1024)
#define APK_RECLAIM_MAX_RANGE_BYTES (256ULL * 1024 * 1024)

struct reclaim_apk {
  const char *path;
  struct stat st;
  int fd;
};

static unsigned long long reclaim_start_time(int pid) {
  char path[64], data[4096];
  snprintf(path, sizeof(path), "/proc/%d/stat", pid);
  FILE *file = fopen(path, "re");
  if (file == NULL)
    return 0;
  char *ok = fgets(data, sizeof(data), file);
  fclose(file);
  if (ok == NULL)
    return 0;
  char *cursor = strrchr(data, ')');
  if (cursor == NULL || cursor[1] != ' ')
    return 0;
  cursor += 2;
  char *save = NULL;
  for (int field = 3; field <= 22; ++field) {
    char *token = strtok_r(field == 3 ? cursor : NULL, " ", &save);
    if (token == NULL)
      return 0;
    if (field == 22)
      return strtoull(token, NULL, 10);
  }
  return 0;
}

static int reclaim_mapping_unchanged(int pid, const char *expected) {
  char path[64], line[8192];
  snprintf(path, sizeof(path), "/proc/%d/maps", pid);
  FILE *file = fopen(path, "re");
  if (file == NULL)
    return 0;
  int found = 0;
  while (fgets(line, sizeof(line), file) != NULL) {
    if (strcmp(line, expected) == 0) {
      found = 1;
      break;
    }
  }
  fclose(file);
  return found;
}

static int reclaim_mapped_apks(int file_count, char **paths) {
  struct reclaim_apk targets[APK_RECLAIM_MAX_FILES];
  int opened = 0, status = EXIT_FAILURE;
  DIR *directory = NULL;
  long page_size = sysconf(_SC_PAGESIZE);
  if (file_count < 1 || file_count > APK_RECLAIM_MAX_FILES || page_size <= 0) {
    fprintf(stderr, "Invalid mapped APK reclaim target count/page size\n");
    return EXIT_FAILURE;
  }
  for (int index = 0; index < file_count; ++index) {
    struct reclaim_apk *target = &targets[index];
    target->path = paths[index];
    size_t length = strlen(target->path);
    if (strncmp(target->path, "/data/app/", 10) != 0 || length < 4 ||
        strcmp(target->path + length - 4, ".apk") != 0 ||
        strstr(target->path, "/../") != NULL ||
        strchr(target->path, '\n') != NULL ||
        strchr(target->path, '\t') != NULL) {
      fprintf(stderr, "Reclaim requires exact installed /data/app APK paths\n");
      goto cleanup;
    }
    target->fd = open(target->path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (target->fd < 0) {
      fprintf(stderr, "Unable to open essential APK %s: %s\n", target->path,
              strerror(errno));
      goto cleanup;
    }
    ++opened;
    if (fstat(target->fd, &target->st) != 0 || !S_ISREG(target->st.st_mode) ||
        target->st.st_size <= 0) {
      fprintf(stderr, "Invalid essential APK %s\n", target->path);
      goto cleanup;
    }
  }
  int self_pidfd = syscall(__NR_pidfd_open, getpid(), 0);
  if (self_pidfd < 0) {
    fprintf(stderr, "pidfd_open unavailable: %s\n", strerror(errno));
    goto cleanup;
  }
  close(self_pidfd);
  directory = opendir("/proc");
  if (directory == NULL)
    goto cleanup;
  /* This mode runs before the recorder installs its signal handlers. Bound the
   * whole helper even if a large maps file or one syscall takes unexpectedly
   * long. */
  signal(SIGALRM, SIG_DFL);
  alarm(5);
  printf("pid\tstarttime\tbegin\tend\toffset\tdev\tinode\tpermissions\trequeste"
         "d\tresult\terrno\tpath\n");
  struct timespec started;
  clock_gettime(CLOCK_MONOTONIC, &started);
  struct dirent *entry;
  unsigned long long total = 0;
  unsigned count = 0;
  while ((entry = readdir(directory)) != NULL) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    if (now.tv_sec - started.tv_sec >= 5) {
      fprintf(stderr, "Mapped APK reclaim reached 5-second limit\n");
      goto cleanup;
    }
    char *endptr;
    long pid_value = strtol(entry->d_name, &endptr, 10);
    if (*endptr != '\0' || pid_value <= 1 || pid_value > INT_MAX ||
        pid_value == getpid())
      continue;
    int pid = (int)pid_value;
    unsigned long long identity = reclaim_start_time(pid);
    if (identity == 0)
      continue;
    int pidfd = syscall(__NR_pidfd_open, pid, 0);
    if (pidfd < 0)
      continue; /* Other processes can exit during enumeration. */
    char path[64], line[8192];
    snprintf(path, sizeof(path), "/proc/%d/maps", pid);
    FILE *file = fopen(path, "re");
    if (file == NULL) {
      close(pidfd);
      continue;
    }
    while (fgets(line, sizeof(line), file) != NULL) {
      unsigned long long begin, end, offset, inode;
      unsigned dev_major, dev_minor;
      char permissions[5], name[4096];
      if (sscanf(line, "%llx-%llx %4s %llx %x:%x %llu %4095[^\n]", &begin, &end,
                 permissions, &offset, &dev_major, &dev_minor, &inode,
                 name) != 8)
        continue;
      if (permissions[0] != 'r' || permissions[1] != '-' ||
          permissions[2] != '-' ||
          (permissions[3] != 's' && permissions[3] != 'p'))
        continue;
      if (end <= begin || begin % page_size != 0 || end % page_size != 0 ||
          offset % page_size != 0 || end - begin > APK_RECLAIM_MAX_RANGE_BYTES)
        continue;
      for (int index = 0; index < file_count; ++index) {
        struct reclaim_apk *target = &targets[index];
        if (strcmp(name, target->path) != 0 || inode != target->st.st_ino ||
            dev_major != major(target->st.st_dev) ||
            dev_minor != minor(target->st.st_dev))
          continue;
        struct stat current;
        uint64_t file_limit = ((uint64_t)target->st.st_size + page_size - 1) /
                              page_size * page_size;
        if (stat(target->path, &current) != 0 ||
            current.st_dev != target->st.st_dev ||
            current.st_ino != target->st.st_ino ||
            current.st_size != target->st.st_size || offset > file_limit ||
            end - begin > file_limit - offset ||
            reclaim_start_time(pid) != identity ||
            !reclaim_mapping_unchanged(pid, line))
          continue;
        clock_gettime(CLOCK_MONOTONIC, &now);
        if (++count > APK_RECLAIM_MAX_RANGES ||
            total + end - begin > APK_RECLAIM_MAX_BYTES ||
            now.tv_sec - started.tv_sec >= 5) {
          fprintf(stderr, "Mapped APK reclaim reached range/byte/time limit\n");
          fclose(file);
          close(pidfd);
          goto cleanup;
        }
        total += end - begin;
        struct iovec range = {.iov_base = (void *)(uintptr_t)begin,
                              .iov_len = end - begin};
        errno = 0;
        long result =
            syscall(__NR_process_madvise, pidfd, &range, 1, MADV_PAGEOUT, 0);
        int saved_errno = errno;
        printf(
            "%d\t%llu\t%llx\t%llx\t%llx\t%x:%x\t%llu\t%s\t%llu\t%ld\t%d\t%s\n",
            pid, identity, begin, end, offset, dev_major, dev_minor, inode,
            permissions, end - begin, result, saved_errno, target->path);
        fflush(stdout);
        if (reclaim_start_time(pid) != identity ||
            !reclaim_mapping_unchanged(pid, line)) {
          fprintf(stderr,
                  "PID %d mapping changed during advice; stop reclaiming\n",
                  pid);
          fclose(file);
          close(pidfd);
          goto cleanup;
        }
      }
    }
    fclose(file);
    close(pidfd);
  }
  status = EXIT_SUCCESS;
cleanup:
  alarm(0);
  if (directory != NULL)
    closedir(directory);
  for (int index = 0; index < opened; ++index)
    close(targets[index].fd);
  return status;
}
#endif
