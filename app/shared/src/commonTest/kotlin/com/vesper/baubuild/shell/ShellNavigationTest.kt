package com.vesper.baubuild.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellNavigationTest {
    @Test fun startsOnProjectsNotSearching() {
        val nav = ShellNavigation()
        assertEquals(AppTab.Projects, nav.selected)
        assertFalse(nav.isSearching)
    }

    @Test fun exitSearchReturnsToPreviousTab() {
        val nav = ShellNavigation()
        nav.select(AppTab.Review)
        nav.enterSearch()
        assertTrue(nav.isSearching)
        assertEquals(AppTab.Review, nav.returnTab)
        assertTrue(nav.exitSearch())
        assertEquals(AppTab.Review, nav.selected)
    }

    @Test fun exitSearchWhenNotSearchingIsNoOp() {
        val nav = ShellNavigation()
        nav.select(AppTab.Profile)
        assertFalse(nav.exitSearch())
        assertEquals(AppTab.Profile, nav.selected)
    }

    @Test fun selectingTabFromSearchUpdatesReturnTab() {
        val nav = ShellNavigation()
        nav.enterSearch()
        nav.select(AppTab.MyWork) // e.g. from the wide rail
        assertEquals(AppTab.MyWork, nav.selected)
        assertEquals(AppTab.MyWork, nav.returnTab)
    }

    @Test fun restoredInSearchFallsBackToProjects() {
        val nav = ShellNavigation(initial = AppTab.Search, returnTab = AppTab.Search)
        assertEquals(AppTab.Projects, nav.returnTab)
        nav.exitSearch()
        assertEquals(AppTab.Projects, nav.selected)
    }
}
