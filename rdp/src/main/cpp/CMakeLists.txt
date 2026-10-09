cmake_minimum_required(VERSION 3.22.1)
project(miffanrdp LANGUAGES C CXX)
set(NATIVE "${CMAKE_CURRENT_LIST_DIR}/../../../build/native/${ANDROID_ABI}")
foreach(ARCHIVE freerdp-client3 freerdp3 winpr3 ssl crypto)
    if(NOT EXISTS "${NATIVE}/lib/lib${ARCHIVE}.a")
        message(FATAL_ERROR "Missing ${ARCHIVE} for ${ANDROID_ABI}. Run rdp/scripts/build-native.sh (arm64-v8a x86_64) from the repository root.")
    endif()
endforeach()
add_library(miffanrdp SHARED rdp_jni.cpp)
target_compile_features(miffanrdp PRIVATE cxx_std_17)
target_compile_options(miffanrdp PRIVATE -Wall -Wextra -Werror=return-type)
target_include_directories(miffanrdp PRIVATE "${NATIVE}/include/freerdp3" "${NATIVE}/include/winpr3" "${NATIVE}/include")
target_link_libraries(miffanrdp PRIVATE -Wl,--start-group
    "${NATIVE}/lib/libfreerdp-client3.a" "${NATIVE}/lib/libfreerdp3.a" "${NATIVE}/lib/libwinpr3.a"
    "${NATIVE}/lib/libssl.a" "${NATIVE}/lib/libcrypto.a" -Wl,--end-group
    mediandk android log z dl)
target_link_options(miffanrdp PRIVATE -Wl,--wrap=AMediaCodec_createDecoderByType -Wl,--wrap=GetKnownPath -Wl,--wrap=freerdp_GetConfigFilePath -Wl,-z,max-page-size=16384 -Wl,--exclude-libs,ALL)
