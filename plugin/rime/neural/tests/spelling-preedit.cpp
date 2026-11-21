// SPDX-License-Identifier: MIT
#include "spelling-preedit.h"
#include <cassert>

int main() {
    using chinese_ime_lm::spellingPreedit;
    const auto automatic = spellingPreedit("nihao", 2, {2});
    assert(automatic && automatic->text == "ni'hao" && automatic->cursor == 3);
    assert((automatic->rawPositions == std::vector<size_t>{0, 1, 2, 2, 3, 4, 5}));

    const auto explicitSplit = spellingPreedit("ni'hao", 2, {2, 3});
    assert(explicitSplit && explicitSplit->text == "ni'hao" && explicitSplit->cursor == 2);
    assert((explicitSplit->rawPositions == std::vector<size_t>{0, 1, 2, 3, 4, 5, 6}));

    const auto atEnd = spellingPreedit("nihaoma", 7, {2, 5});
    assert(atEnd && atEnd->text == "ni'hao'ma" && atEnd->cursor == 9);
    assert(!spellingPreedit("`nihao", 0, {}));
}
