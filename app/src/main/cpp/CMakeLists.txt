cmake_minimum_required(VERSION 3.22)
project(fjair C)
find_package(openssl REQUIRED CONFIG)

file(GLOB UX uxplay/*.c uxplay/playfair/*.c uxplay/llhttp/*.c)
file(GLOB PL plist/src/*.c plist/libcnary/*.c)
list(APPEND UX uxplay/dns_sd_impl.c)

add_library(fjair SHARED native.c shim/dns_sd_shim.c ${UX} ${PL})
target_include_directories(fjair PRIVATE shim uxplay uxplay/playfair uxplay/llhttp
    plist plist/include plist/src plist/libcnary/include)
target_compile_definitions(fjair PRIVATE NOHOLD STANDALONE TARGET_POSIX _LINUX _REENTRANT
    HAVE_STRNDUP HAVE_MEMMEM HAVE_LOCALTIME_R HAVE_GMTIME_R HAVE_TM_TM_GMTOFF HAVE_TM_TM_ZONE HAVE_STRPTIME
    LIBPLIST_STATIC PACKAGE_VERSION="2.7.0")
target_compile_options(fjair PRIVATE -O2 -fvisibility=hidden -w)
target_link_libraries(fjair openssl::ssl openssl::crypto log)
