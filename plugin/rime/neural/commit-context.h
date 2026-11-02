// SPDX-License-Identifier: MIT
#pragma once
#include <string>

namespace chinese_ime_lm {
// Text this Rime session already committed, oldest first, used as left context
// for the next input. Rime keeps at most 20 records in memory and clears them on
// an unhandled Return. Pass-through keys ("thru") and committed raw pinyin
// letters ("raw") break contiguous Chinese text and truncate earlier history.
template <typename Records>
std::string committedContext(const Records &records) {
    std::string text;
    for (const auto &record : records) {
        if (record.type == "thru" || record.type == "raw") text.clear();
        else text += record.text;
    }
    return text;
}

inline void popUtf8CodePoint(std::string &text) {
    while (!text.empty() && (static_cast<unsigned char>(text.back()) & 0xC0) == 0x80)
        text.pop_back();
    if (!text.empty()) text.pop_back();
}

// An unhandled BackSpace deletes one character in the host editor rather than
// the entire line. Pop one UTF-8 code point from the latest committed record so
// deleting a miscommitted word preserves the earlier words before it.
template <typename Records>
void popCommittedCharacter(Records &records) {
    while (!records.empty() && records.back().text.empty()) records.pop_back();
    if (records.empty()) return;
    if (records.back().type == "thru" || records.back().type == "raw") {
        records.clear();
        return;
    }
    popUtf8CodePoint(records.back().text);
    if (records.back().text.empty()) records.pop_back();
}
} // namespace chinese_ime_lm
