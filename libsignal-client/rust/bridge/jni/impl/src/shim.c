#include <errno.h>
#include <sys/epoll.h>
#include <fcntl.h>
#include <string.h>

__attribute__((visibility("hidden")))
int epoll_create1(int flags) {
    int fd = epoll_create(1);
    if (fd < 0) return fd;
    if (flags & EPOLL_CLOEXEC) {
        fcntl(fd, F_SETFD, FD_CLOEXEC);
    }
    return fd;
}

__attribute__((visibility("hidden")))
void *__memset_chk(void *dest, int c, size_t len, size_t destlen) {
    return memset(dest, c, len);
}

__attribute__((visibility("hidden")))
void *__memcpy_chk(void *dest, const void *src, size_t len, size_t destlen) {
    return memcpy(dest, src, len);
}

__attribute__((visibility("hidden")))
ssize_t __read_chk(int fd, void *buf, size_t nbytes, size_t buflen) {
    extern ssize_t read(int, void *, size_t);
    return read(fd, buf, nbytes);
}

__attribute__((visibility("hidden")))
size_t __strlen_chk(const char *s, size_t s_len) {
    return strlen(s);
}

__attribute__((visibility("hidden")))
int __vsnprintf_chk(char *s, size_t maxlen, int flag, size_t slen, const char *format, void *args) {
    extern int vsnprintf(char *, size_t, const char *, void *);
    return vsnprintf(s, maxlen, format, args);
}

// Ensure __stack_chk_fail and __stack_chk_guard are hidden as well if possible, though compiler might complain.

__attribute__((visibility("hidden")))
void __stack_chk_fail(void) {
    extern void abort(void);
    abort();
}

__attribute__((visibility("hidden")))
void *__stack_chk_guard = (void*)0xdeadbeef;

#include <sys/syscall.h>
#include <unistd.h>

__attribute__((visibility("hidden")))
ssize_t getrandom(void *buf, size_t buflen, unsigned int flags) {
    errno = ENOSYS;
    return -1;
}

__attribute__((visibility("hidden")))
int getentropy(void *buf, size_t buflen) {
    errno = ENOSYS;
    return -1;
}

__attribute__((visibility("hidden")))
pid_t gettid(void) {
#ifdef __NR_gettid
    return syscall(__NR_gettid);
#else
    return getpid();
#endif
}
