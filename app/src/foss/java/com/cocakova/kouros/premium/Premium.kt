package com.cocakova.kouros.premium

/**
 * The supporter seam. This is the free build's version: it reports that no supporter features
 * are present and adds nothing to the UI. Everything the app does lives in the free build.
 * (A private flavor may define this object differently; keep the signatures in sync.)
 */
object Premium {
    const val isSupporter: Boolean = false
}
