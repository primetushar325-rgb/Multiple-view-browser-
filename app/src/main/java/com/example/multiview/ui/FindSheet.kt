package com.example.multiview.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.TextView
import com.example.multiview.R
import com.example.multiview.panes.PaneView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONObject

/**
 * Find-in-page for one pane.
 *
 * Highlighting and next/prev use the WebView's own findAllAsync/findNext.
 * The match count cannot come from WebChromeClient.onFindResultReceived: that
 * callback was deprecated in API 33 and is gone from the API 34+ platform
 * class, so it cannot be overridden at all. The count is therefore read from
 * the page's rendered text instead, which also matches what the user sees.
 */
class FindSheet(
    context: Context,
    private val pane: PaneView?,
) : BottomSheetDialog(context) {

    private var active = 0
    private var total = 0
    private val label: TextView

    init {
        val root = LayoutInflater.from(context).inflate(R.layout.sheet_find, null)
        setContentView(root)

        label = root.findViewById(R.id.tvMatches)
        val input = root.findViewById<TextInputEditText>(R.id.etFind)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = runFind(s?.toString().orEmpty())
        })

        root.findViewById<ImageButton>(R.id.btnNext).setOnClickListener { step(forward = true) }
        root.findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { step(forward = false) }
        root.findViewById<ImageButton>(R.id.btnFindClose).setOnClickListener { dismiss() }

        setOnDismissListener { pane?.webView?.clearMatches() }
    }

    private fun runFind(query: String) {
        val webView = pane?.webView ?: return
        if (query.isEmpty()) {
            webView.clearMatches()
            active = 0; total = 0
            render()
            return
        }
        webView.findAllAsync(query)
        countMatches(query)
    }

    /** Counts occurrences in the rendered text. Returns 0 on any failure. */
    private fun countMatches(query: String) {
        val webView = pane?.webView ?: return
        // JSONObject.quote produces a safe JS string literal for any input.
        val needle = JSONObject.quote(query.lowercase())
        val js = """
            (function(){
              try {
                var t = document.body ? (document.body.innerText || "") : "";
                var q = $needle;
                if (!q) return 0;
                t = t.toLowerCase();
                var n = 0, i = 0;
                while ((i = t.indexOf(q, i)) !== -1) { n++; i += q.length; }
                return n;
              } catch (e) { return 0; }
            })()
        """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            val n = result?.trim()?.trim('"')?.toIntOrNull() ?: 0
            total = n
            active = if (n > 0) 1 else 0
            render()
        }
    }

    private fun step(forward: Boolean) {
        if (total == 0) return
        pane?.webView?.findNext(forward)
        active = ((active - 1 + (if (forward) 1 else -1)) % total + total) % total + 1
        render()
    }

    private fun render() {
        label.text = if (total == 0) {
            context.getString(R.string.find_no_match)
        } else {
            context.getString(R.string.find_matches, active, total)
        }
    }
}
