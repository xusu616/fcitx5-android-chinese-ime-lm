/* SPDX-License-Identifier: LGPL-2.1-or-later */
#pragma once

#include <fcitx/addoninstance.h>

namespace fcitx {
class InputContext;
}

FCITX_ADDON_DECLARE_FUNCTION(NeuralRimePreedit, moveCursor,
                             void(fcitx::AddonInstance *, fcitx::InputContext *, int))
