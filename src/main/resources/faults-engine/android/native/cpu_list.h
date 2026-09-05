#ifndef MPERF_CPU_LIST_H_
#define MPERF_CPU_LIST_H_

#include <errno.h>
#include <limits.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>

#define MPERF_MAX_CPU_ID 1048575L

/*
 * Parse Linux's cpulist syntax, for example "0-3,8,10-11".
 *
 * The caller owns *cpus_out. Values are returned in the order represented by
 * the canonical sysfs list. Duplicate or descending ranges are rejected so a
 * malformed topology can never silently produce duplicate perf events.
 */
static int parse_cpu_list(const char *text, int **cpus_out, size_t *count_out,
                          char *error, size_t error_size) {
  if (text == NULL || cpus_out == NULL || count_out == NULL) {
    snprintf(error, error_size, "invalid cpu-list arguments");
    return -1;
  }

  int *cpus = NULL;
  size_t count = 0;
  size_t capacity = 0;
  const char *cursor = text;
  while (*cursor != '\0' && *cursor != '\n') {
    char *end = NULL;
    errno = 0;
    const long first = strtol(cursor, &end, 10);
    if (errno != 0 || end == cursor || first < 0 ||
        first > MPERF_MAX_CPU_ID) {
      snprintf(error, error_size, "invalid cpu id near %.32s", cursor);
      free(cpus);
      return -1;
    }

    long last = first;
    cursor = end;
    if (*cursor == '-') {
      cursor += 1;
      errno = 0;
      last = strtol(cursor, &end, 10);
      if (errno != 0 || end == cursor || last < first ||
          last > MPERF_MAX_CPU_ID) {
        snprintf(error, error_size, "invalid cpu range near %.32s", cursor);
        free(cpus);
        return -1;
      }
      cursor = end;
    }

    for (long cpu = first; cpu <= last; ++cpu) {
      if (count > 0 && cpus[count - 1] >= cpu) {
        snprintf(error, error_size, "cpu list is not strictly increasing");
        free(cpus);
        return -1;
      }
      if (count == capacity) {
        const size_t new_capacity = capacity == 0 ? 16 : capacity * 2;
        int *resized = realloc(cpus, new_capacity * sizeof(*cpus));
        if (resized == NULL) {
          snprintf(error, error_size, "unable to allocate cpu list");
          free(cpus);
          return -1;
        }
        cpus = resized;
        capacity = new_capacity;
      }
      cpus[count++] = (int)cpu;
    }

    if (*cursor == ',') {
      cursor += 1;
      if (*cursor == '\0' || *cursor == '\n') {
        snprintf(error, error_size, "cpu list has a trailing comma");
        free(cpus);
        return -1;
      }
    } else if (*cursor != '\0' && *cursor != '\n') {
      snprintf(error, error_size, "invalid cpu-list delimiter near %.32s",
               cursor);
      free(cpus);
      return -1;
    }
  }

  if (count == 0) {
    snprintf(error, error_size, "cpu list is empty");
    free(cpus);
    return -1;
  }
  *cpus_out = cpus;
  *count_out = count;
  return 0;
}

#endif  // MPERF_CPU_LIST_H_
