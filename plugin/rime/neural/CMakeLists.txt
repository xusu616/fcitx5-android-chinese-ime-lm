# SPDX-License-Identifier: MIT
set(NEURAL_DIR "${CMAKE_CURRENT_LIST_DIR}")
set(NEURAL_RUST_ARCHIVE "${NEURAL_DIR}/target/aarch64-linux-android/release/librime_chinese_ime_lm.a")
if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "The neural plugin currently targets arm64-v8a")
endif()
# Internal Rime classes have no stable ABI; the Android build also patches their
# layout. Refuse an unreviewed library update instead of compiling stale headers.
file(SHA256 "${PREBUILT_DIR}/librime/${ANDROID_ABI}/lib/librime.a" RIME_ARCHIVE_SHA256)
if(NOT RIME_ARCHIVE_SHA256 STREQUAL "27c157d9080fabf5ab0261d3fdb40e02ba0c177c3db24196f7c5217e6be72db8")
    message(FATAL_ERROR "The Rime prebuilt changed; update the matching patched internal headers first")
endif()
if(NOT EXISTS "${NEURAL_RUST_ARCHIVE}")
    message(FATAL_ERROR "Build the neural Rust library before building this variant")
endif()
set(NEURAL_MODEL "${NEURAL_DIR}/model/sentence-model.safetensors")
if(NOT EXISTS "${NEURAL_MODEL}")
    message(FATAL_ERROR "Run neural/download-model.ps1 before building this variant")
endif()
file(SHA256 "${NEURAL_MODEL}" MODEL_SHA256)
if(NOT MODEL_SHA256 STREQUAL "86ac529510cb3b4968a5e6ade83ec8080f5b34a0a681e75c362accbbd387d1c1")
    message(FATAL_ERROR "The bundled chinese-ime-lm model checksum does not match")
endif()

add_library(chinese_ime_lm STATIC IMPORTED)
set_target_properties(chinese_ime_lm PROPERTIES IMPORTED_LOCATION "${NEURAL_RUST_ARCHIVE}")
# The pinned prebuilt librime enables glog, but only installs its public C API.
# Internal headers used by this filter need this generated configuration too.
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/neural-include/rime/build_config.h"
    "#pragma once\n#define RIME_ENABLE_LOGGING\n")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/neural-include/X11/keysym.h"
    "#pragma once\n")
set(NEURAL_CORRECTION_SOURCES "${NEURAL_DIR}/correction-distance.cpp")
set(NEURAL_CUSTOM_PHRASE_SOURCE "${NEURAL_DIR}/custom-phrase.cpp")
set(NEURAL_SYLLABIFIER_SYMBOL "_ZN4rime11Syllabifier18BuildSyllableGraphERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEERNS_5PrismEPNS_13SyllableGraphE")
target_sources(rime PRIVATE "${NEURAL_DIR}/neural-reranker.cpp"
    "${NEURAL_DIR}/rime-preedit.cpp" ${NEURAL_CORRECTION_SOURCES} ${NEURAL_CUSTOM_PHRASE_SOURCE})
target_link_options(rime PRIVATE "LINKER:--wrap=${NEURAL_SYLLABIFIER_SYMBOL}")
# librime 1.16.1 headers use C++17 filesystem::path::u8string semantics.
set_source_files_properties("${NEURAL_DIR}/neural-reranker.cpp" ${NEURAL_CORRECTION_SOURCES}
    ${NEURAL_CUSTOM_PHRASE_SOURCE}
    TARGET_DIRECTORY rime PROPERTIES COMPILE_OPTIONS "-std=c++17")
target_compile_definitions(rime PRIVATE GLOG_USE_GLOG_EXPORT)
target_include_directories(rime PRIVATE
    "${NEURAL_DIR}"
    "${NEURAL_DIR}/vendor/librime/src"
    "${CMAKE_CURRENT_BINARY_DIR}/neural-include"
    "${PREBUILT_DIR}/boost/${ANDROID_ABI}/include"
    "${PREBUILT_DIR}/glog/${ANDROID_ABI}/include"
    "${PREBUILT_DIR}/marisa/${ANDROID_ABI}/include"
)
target_link_libraries(rime chinese_ime_lm dl log m)

# Explicit test target; never installed in or packaged with the plugin.
add_executable(rime-neural-smoke EXCLUDE_FROM_ALL
    "${NEURAL_DIR}/tests/rime-filter-smoke.cpp"
    "${NEURAL_DIR}/neural-reranker.cpp" ${NEURAL_CORRECTION_SOURCES} ${NEURAL_CUSTOM_PHRASE_SOURCE})
set_target_properties(rime-neural-smoke PROPERTIES CXX_STANDARD 17)
target_link_options(rime-neural-smoke PRIVATE
    "LINKER:--wrap=${NEURAL_SYLLABIFIER_SYMBOL}"
    "LINKER:--wrap=__android_log_print")
target_include_directories(rime-neural-smoke PRIVATE
    "${NEURAL_DIR}" "${NEURAL_DIR}/vendor/librime/src"
    "${CMAKE_CURRENT_BINARY_DIR}/neural-include"
    "${PREBUILT_DIR}/boost/${ANDROID_ABI}/include"
    "${PREBUILT_DIR}/glog/${ANDROID_ABI}/include"
    "${PREBUILT_DIR}/marisa/${ANDROID_ABI}/include")
target_compile_definitions(rime-neural-smoke PRIVATE GLOG_USE_GLOG_EXPORT)
target_link_libraries(rime-neural-smoke PRIVATE ${RIME_TARGET} chinese_ime_lm dl log m)

# Hook the pinned upstream files in the build directory. Leave the submodule
# pristine and fail if any anchor changes during an upstream update.
set(ENGINE_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/fcitx5-rime/src/rimeengine.cpp")
set(STATE_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/fcitx5-rime/src/rimestate.cpp")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${ENGINE_SOURCE}" "${STATE_SOURCE}")
file(READ "${ENGINE_SOURCE}" ENGINE_CONTENT)
set(INITIALIZE_LINE "api_->initialize(&fcitx_rime_traits);")
string(FIND "${ENGINE_CONTENT}" "${INITIALIZE_LINE}" INITIALIZE_POSITION)
if(INITIALIZE_POSITION EQUAL -1)
    message(FATAL_ERROR "The upstream Rime initialization hook changed")
endif()
string(REPLACE "${INITIALIZE_LINE}"
    "${INITIALIZE_LINE}\n    registerChineseImeLm(fcitx_rime_traits.user_data_dir, fcitx_rime_traits.shared_data_dir);"
    ENGINE_CONTENT "${ENGINE_CONTENT}")
set(REGISTER_LINE "    constructed_ = true;")
string(FIND "${ENGINE_CONTENT}" "${REGISTER_LINE}" REGISTER_POSITION)
if(REGISTER_POSITION EQUAL -1)
    message(FATAL_ERROR "The upstream Rime engine constructor hook changed")
endif()
string(REPLACE "${REGISTER_LINE}" "    registerNeuralRimePreedit(*this);\n${REGISTER_LINE}"
    ENGINE_CONTENT "${ENGINE_CONTENT}")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/rimeengine-neural.cpp"
    "#include \"neural-reranker.h\"\n#include \"rime-preedit.h\"\n${ENGINE_CONTENT}")
file(READ "${STATE_SOURCE}" STATE_CONTENT)
set(PREEDIT_LINE "        updatePreedit(ic, context);")
string(FIND "${STATE_CONTENT}" "${PREEDIT_LINE}" PREEDIT_POSITION)
if(PREEDIT_POSITION EQUAL -1)
    message(FATAL_ERROR "The upstream Rime preedit hook changed")
endif()
string(REPLACE "${PREEDIT_LINE}" "${PREEDIT_LINE}\n        updateNeuralRimePreedit(*ic, session);"
    STATE_CONTENT "${STATE_CONTENT}")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/rimestate-neural.cpp"
    "#include \"rime-preedit.h\"\n${STATE_CONTENT}")
get_target_property(ENGINE_SOURCES rime SOURCES)
list(FILTER ENGINE_SOURCES EXCLUDE REGEX "(^|/)rime(engine|state)\\.cpp$")
set_property(TARGET rime PROPERTY SOURCES "${ENGINE_SOURCES}")
target_sources(rime PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/rimeengine-neural.cpp"
    "${CMAKE_CURRENT_BINARY_DIR}/rimestate-neural.cpp")
target_include_directories(rime PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/fcitx5-rime/src")

install(FILES "${NEURAL_DIR}/LICENSE"
    "${NEURAL_DIR}/../../../NOTICE"
    "${NEURAL_DIR}/../../../LICENSES/LGPL-2.1.txt"
    DESTINATION "${FCITX_INSTALL_DATADIR}/rime-data/rime-chinese-ime-lm"
    COMPONENT prebuilt-assets)
install(FILES "${NEURAL_DIR}/vendor/librime/LICENSE"
    DESTINATION "${FCITX_INSTALL_DATADIR}/rime-data/rime-chinese-ime-lm"
    RENAME "RIME-BSD-3-Clause.txt"
    COMPONENT prebuilt-assets)
install(FILES "${NEURAL_DIR}/vendor/librime/DARTS_COPYING.md"
    DESTINATION "${FCITX_INSTALL_DATADIR}/rime-data/rime-chinese-ime-lm"
    RENAME "DARTS-BSD-2-Clause.txt"
    COMPONENT prebuilt-assets)
install(FILES "${NEURAL_DIR}/vendor/chinese-ime-lm/LICENSE"
    "${NEURAL_DIR}/vendor/chinese-ime-lm/NOTICE"
    DESTINATION "${FCITX_INSTALL_DATADIR}/rime-data/chinese-ime-lm"
    COMPONENT prebuilt-assets)
install(FILES "${NEURAL_MODEL}"
    DESTINATION "${FCITX_INSTALL_DATADIR}/rime-data"
    COMPONENT prebuilt-assets)
