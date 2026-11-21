/* SPDX-License-Identifier: LGPL-2.1-or-later */
#include "rime-preedit.h"
#include "rime-preedit-api.h"
#include "rimeengine.h"
#include "rimestate.h"

#include <fcitx/inputcontext.h>
#include <fcitx/inputpanel.h>

namespace {
class PreeditCursor final : public fcitx::AddonFunctionAdaptorErasure<
                                fcitx::INeuralRimePreedit::moveCursor::Signature> {
    void callback(fcitx::AddonInstance *addon, fcitx::InputContext *ic, int position) override {
        auto &engine = static_cast<fcitx::rime::RimeEngine &>(*addon);
        auto *state = engine.state(ic);
        if (!state || engine.api()->is_maintenance_mode()) return;
        const auto session = state->session(false);
        const auto preedit = rimeSpellingPreedit(session);
        if (!preedit || position < 0 || static_cast<size_t>(position) >= preedit->rawPositions.size())
            return;

        const auto caret = preedit->rawPositions[position];
        if (caret != engine.api()->get_caret_pos(session)) {
            engine.api()->set_caret_pos(session, caret);
            state->updateUI(ic, false);
        }
    }
};
} // namespace

void registerNeuralRimePreedit(fcitx::rime::RimeEngine &engine) {
    static PreeditCursor cursor;
    engine.registerCallback(fcitx::INeuralRimePreedit::moveCursor::Name::data(), &cursor);
}

void updateNeuralRimePreedit(fcitx::InputContext &ic, RimeSessionId sessionId) {
    const auto preedit = rimeSpellingPreedit(sessionId);
    if (!preedit) return;

    // This row displays input spelling. Fcitx's client preedit retains its own
    // composing text, such as the converted Chinese preview in the editor.
    fcitx::Text text;
    text.append(preedit->text, fcitx::TextFormatFlag::DontCommit);
    text.setCursor(preedit->cursor);
    ic.inputPanel().setPreedit(std::move(text));
}
