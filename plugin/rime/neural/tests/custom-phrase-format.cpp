// SPDX-License-Identifier: MIT
#include "custom-phrase-format.h"
#include <cassert>

int main() {
    using chinese_ime_lm::equalsPhraseColumns;
    assert((equalsPhraseColumns("嗯哼=eh") == std::vector<std::string>{"嗯哼", "eh"}));
    assert((equalsPhraseColumns("两个 字 = er ge = 3\r") ==
            std::vector<std::string>{"两个 字", "er ge", "3"}));
    assert(equalsPhraseColumns("嗯哼   eh").empty());
    assert(equalsPhraseColumns("=eh").empty());
    assert(equalsPhraseColumns("嗯哼= =3").empty());
    assert(equalsPhraseColumns("嗯哼=eh=3=1").empty());
}
