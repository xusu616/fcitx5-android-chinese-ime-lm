/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import android.content.Context
import android.graphics.Paint
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.text.Spanned
import android.text.SpannedString
import android.text.style.DynamicDrawableSpan
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.text.buildSpannedString
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.TextFormatFlag
import org.fcitx.fcitx5.android.core.hasFlag
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout

open class PreeditUi(
    override val ctx: Context,
    private val theme: Theme,
    private val setupTextView: (TextView.() -> Unit)? = null,
    private val moveSpellingCursor: ((Int, String) -> Unit)? = null
) : Ui {

    class CursorSpan(ctx: Context, @ColorInt color: Int, metrics: Paint.FontMetricsInt) :
        DynamicDrawableSpan() {
        private val drawable = ShapeDrawable(RectShape()).apply {
            paint.color = color
            setBounds(0, metrics.ascent, ctx.dp(1), metrics.bottom)
        }

        override fun getDrawable() = drawable
    }

    private fun createTextView() = textView {
        setTextColor(theme.keyTextColor)
        textSize = 16f
        setupTextView?.invoke(this)
    }

    private val upView = createTextView()

    private val downView = createTextView()
    val editableSpellingView: View?
        get() = upView.takeIf { it.isShown && it.isClickable }

    private val normalTextSize = upView.textSize
    private var lastPanel: FcitxEvent.InputPanelEvent.Data? = null
    private var rimeActive = false
    private var editing = false
    private var renderedCursor = -1
    private var tappedOffset: Int? = null

    init {
        moveSpellingCursor?.let { move ->
            upView.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL -> tappedOffset = null
                    MotionEvent.ACTION_UP -> tappedOffset = upView.getOffsetForPosition(event.x, event.y)
                }
                false
            }
            upView.setOnClickListener {
                val panel = lastPanel ?: return@setOnClickListener
                if (!canEdit(panel)) return@setOnClickListener
                val preedit = panel.preedit
                val offset = tappedOffset?.let { tapped ->
                    if (renderedCursor >= 0 && tapped > renderedCursor) tapped - 1 else tapped
                } ?: preedit.cursor
                tappedOffset = null
                editing = true
                update(panel)
                move(offset, preedit.toString())
            }
            upView.isClickable = false
        }
    }

    fun setRimeActive(active: Boolean) {
        if (rimeActive == active) return
        rimeActive = active
        lastPanel?.let(::update)
    }

    private fun canEdit(panel: FcitxEvent.InputPanelEvent.Data): Boolean =
        moveSpellingCursor != null && rimeActive && panel.auxUp.isEmpty() &&
            panel.preedit.isNotEmpty() &&
            panel.preedit.flags.singleOrNull()?.hasFlag(TextFormatFlag.DontCommit) == true

    var visible = false
        private set

    override val root: View = verticalLayout {
        add(upView, lParams())
        add(downView, lParams())
    }

    private fun updateTextView(view: TextView, str: CharSequence, visible: Boolean) {
        view.text = str
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun update(inputPanel: FcitxEvent.InputPanelEvent.Data) {
        lastPanel = inputPanel
        val editable = canEdit(inputPanel)
        if (!editable) editing = false
        if (upView.isClickable != editable) {
            upView.isClickable = editable
            // Recompute the IME's touchable insets even if the row keeps its size.
            upView.requestLayout()
        }
        val textSize = if (editing) normalTextSize * 1.5f else normalTextSize
        if (upView.textSize != textSize) upView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)

        val activeBkg = theme.genericActiveBackgroundColor
        val upString: SpannedString
        val upCursor: Int
        if (inputPanel.auxUp.isEmpty()) {
            upString = inputPanel.preedit.toSpannedString(activeBkg)
            upCursor = inputPanel.preedit.cursor
        } else {
            upString = buildSpannedString {
                append(inputPanel.auxUp.toSpannedString(activeBkg))
                append(inputPanel.preedit.toSpannedString(activeBkg))
            }
            upCursor = inputPanel.preedit.cursor.let {
                if (it < 0) it
                else inputPanel.auxUp.length + it
            }
        }
        val downString = inputPanel.auxDown.toSpannedString(activeBkg)
        val hasUp = upString.isNotEmpty()
        val hasDown = downString.isNotEmpty()
        visible = hasUp || hasDown
        if (!visible) {
            updateTextView(upView, "", false)
            updateTextView(downView, "", false)
            return
        }
        renderedCursor = if (upCursor >= 0 && (upCursor < upString.length || editing)) upCursor else -1
        val upStringWithCursor = if (renderedCursor < 0) {
            upString
        } else buildSpannedString {
            if (upCursor > 0) append(upString, 0, upCursor)
            append('|')
            val cursor = CursorSpan(ctx, theme.keyTextColor, upView.paint.fontMetricsInt)
            setSpan(cursor, upCursor, upCursor + 1, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            append(upString, upCursor, upString.length)
        }
        updateTextView(upView, upStringWithCursor, hasUp)
        updateTextView(downView, downString, hasDown)
    }
}
