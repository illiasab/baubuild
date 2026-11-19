package com.vesper.baubuild.shell

/**
 * Desktop (macOS / Windows / Linux) uses the Web portal design (sidebar + wide pages, baybuild.vercel.app → "Web portal").
 * Android and iOS use the mobile ("iOS app") design.
 */
internal expect val usesPortalLayout: Boolean
