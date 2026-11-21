// SPDX-License-Identifier: MIT
#pragma once

#include <algorithm>
#include <optional>
#include <set>
#include <string>
#include <string_view>
#include <vector>

namespace chinese_ime_lm {
struct SpellingPreedit {
    std::string text;
    size_t cursor = 0;
    std::vector<size_t> rawPositions{0};
};

inline std::optional<SpellingPreedit> spellingPreedit(std::string_view input, size_t caret,
                                                     const std::set<size_t> &boundaries) {
    const auto letter = [](char c) { return c >= 'a' && c <= 'z'; };
    const auto spelling = [&](char c) { return letter(c) || c == '\'' || c == ' '; };
    if (!std::all_of(input.begin(), input.end(), spelling)) return std::nullopt;

    SpellingPreedit preedit;
    for (size_t index = 0; index < input.size(); ++index) {
        if (index > 0 && letter(input[index - 1]) && letter(input[index]) &&
            boundaries.count(index)) {
            preedit.text += '\'';
            preedit.rawPositions.push_back(index);
        }
        if (index == caret) preedit.cursor = preedit.text.size();
        preedit.text += input[index];
        preedit.rawPositions.push_back(index + 1);
    }
    if (caret == input.size()) preedit.cursor = preedit.text.size();
    return preedit;
}
} // namespace chinese_ime_lm
