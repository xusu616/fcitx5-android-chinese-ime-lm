// SPDX-License-Identifier: MIT
#include "commit-context.h"
#include <cassert>
#include <string>
#include <vector>

struct Record {
    std::string type;
    std::string text;
};

int main() {
    using chinese_ime_lm::committedContext;
    using chinese_ime_lm::popCommittedCharacter;
    assert(committedContext(std::vector<Record>{}).empty());
    // Earlier commits are joined oldest first, then fed to the model as context.
    assert(committedContext(std::vector<Record>{
        {"phrase", "我想"}, {"punct", "，"}, {"sentence", "吃一个"}}) == "我想，吃一个");
    // Pass-through keys and committed raw pinyin break contiguous Chinese context
    // and truncate history before them.
    assert(committedContext(std::vector<Record>{
        {"phrase", "还在"}, {"thru", " "}}) == "");
    assert(committedContext(std::vector<Record>{
        {"phrase", "今天"}, {"thru", " "}, {"raw", "nihao"}, {"phrase", "天气"}}) == "天气");
    // Deleting a miscommitted 2-character word with two BackSpaces preserves the
    // earlier committed word.
    std::vector<Record> history{{"phrase", "交叉"}, {"phrase", "边缘"}};
    popCommittedCharacter(history);
    assert(committedContext(history) == "交叉边");
    popCommittedCharacter(history);
    assert(committedContext(history) == "交叉");
    popCommittedCharacter(history);
    assert(committedContext(history) == "交");
    popCommittedCharacter(history);
    assert(committedContext(history).empty());
}
