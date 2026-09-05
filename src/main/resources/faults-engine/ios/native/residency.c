#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

static void csv_string(const char *value) {
  putchar('"');
  for (const char *cursor = value; *cursor; ++cursor) {
    if (*cursor == '"') putchar('"');
    putchar(*cursor);
  }
  putchar('"');
}

int main(int argc, char **argv) {
  if (argc < 3) {
    fprintf(stderr, "usage: residency PHASE FILE...\n");
    return 64;
  }
  long page_size = sysconf(_SC_PAGESIZE);
  if (page_size <= 0) {
    perror("sysconf");
    return 1;
  }
  puts("phase,file_name,size_bytes,total_pages,resident_pages,resident_fraction");
  for (int index = 2; index < argc; ++index) {
    int descriptor = open(argv[index], O_RDONLY);
    if (descriptor < 0) {
      fprintf(stderr, "open %s: %s\n", argv[index], strerror(errno));
      return 2;
    }
    struct stat status;
    if (fstat(descriptor, &status) != 0) {
      fprintf(stderr, "stat %s: %s\n", argv[index], strerror(errno));
      close(descriptor);
      return 2;
    }
    size_t size = (size_t)status.st_size;
    size_t pages = size == 0 ? 0 : (size + (size_t)page_size - 1) / (size_t)page_size;
    size_t resident = 0;
    if (size > 0) {
      void *address = mmap(NULL, size, PROT_READ, MAP_PRIVATE, descriptor, 0);
      if (address == MAP_FAILED) {
        fprintf(stderr, "mmap %s: %s\n", argv[index], strerror(errno));
        close(descriptor);
        return 2;
      }
      char *vector = calloc(pages, 1);
      if (vector == NULL || mincore(address, size, vector) != 0) {
        fprintf(stderr, "mincore %s: %s\n", argv[index], strerror(errno));
        free(vector);
        munmap(address, size);
        close(descriptor);
        return 2;
      }
      for (size_t page = 0; page < pages; ++page) {
        if ((((unsigned char)vector[page]) & 1u) != 0) ++resident;
      }
      free(vector);
      munmap(address, size);
    }
    csv_string(argv[1]);
    putchar(',');
    csv_string(argv[index]);
    printf(",%llu,%llu,%llu,%.6f\n",
           (unsigned long long)size,
           (unsigned long long)pages,
           (unsigned long long)resident,
           pages == 0 ? 0.0 : (double)resident / (double)pages);
    close(descriptor);
  }
  return 0;
}
