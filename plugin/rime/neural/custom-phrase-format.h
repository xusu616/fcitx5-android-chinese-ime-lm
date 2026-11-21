// SPDX-License-Identifier: MIT
#pragma once

#include <string>
#include <string_view>
#include <vector>

namespace chinese_ime_lm {
inline std::vector<std::string> equalsPhraseColumns(std::string_view line) {
    const auto trim = [](std::string_view field) {
        const auto start = field.find_first_not_of(" \r");
        if (start == std::string_view::npos) return std::string_view();
        return field.substr(start, field.find_last_not_of(" \r") - start + 1);
    };
    const auto delimiter = line.find('=');
    if (delimiter == std::string_view::npos) return {};

    const auto weightDelimiter = line.find('=', delimiter + 1);
    const auto phrase = trim(line.substr(0, delimiter));
    const auto code = trim(line.substr(delimiter + 1, weightDelimiter == std::string_view::npos
        ? weightDelimiter : weightDelimiter - delimiter - 1));
    if (phrase.empty() || code.empty()) return {};

    std::vector<std::string> columns{std::string(phrase), std::string(code)};
    if (weightDelimiter != std::string_view::npos) {
        const auto weight = trim(line.substr(weightDelimiter + 1));
        if (weight.find('=') != std::string_view::npos) return {};
        columns.emplace_back(weight);
    }
    return columns;
}
} // namespace chinese_ime_lm
