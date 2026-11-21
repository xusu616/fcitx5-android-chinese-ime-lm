/* SPDX-License-Identifier: LGPL-2.1-or-later */
#pragma once

#include "spelling-preedit.h"
#include <rime_api.h>

namespace fcitx {
class InputContext;
namespace rime {
class RimeEngine;
}
}

void registerNeuralRimePreedit(fcitx::rime::RimeEngine &engine);
void updateNeuralRimePreedit(fcitx::InputContext &ic, RimeSessionId session);
std::optional<chinese_ime_lm::SpellingPreedit> rimeSpellingPreedit(RimeSessionId session);
