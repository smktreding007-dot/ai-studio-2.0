package com.bestwinesport.staff

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder

/**
 * Safe navigation for user taps.
 * - Ignores a tap while the previous screen change is still animating (double tap used to open the same screen twice,
 *   so Back had to be pressed two times, or the 2nd Back popped one screen too many).
 * - launchSingleTop: the same screen is never stacked on top of itself.
 */
fun NavController.go(route: String, builder: NavOptionsBuilder.() -> Unit = {}) {
    val entry = currentBackStackEntry
    if (entry != null && entry.lifecycle.currentState != Lifecycle.State.RESUMED) return
    navigate(route) { launchSingleTop = true; builder() }
}

/** Back arrow tap: one tap = one screen back (double tap cannot pop two screens), and never pops the last screen. */
fun NavController.back() {
    val entry = currentBackStackEntry ?: return
    if (entry.lifecycle.currentState != Lifecycle.State.RESUMED) return
    if (previousBackStackEntry == null) return
    popBackStack()
}

/** Login <-> Sign Up links: if that screen is already below in the stack, go back to it instead of stacking login/signup/login/signup... */
fun NavController.goOrBack(route: String) {
    val entry = currentBackStackEntry
    if (entry != null && entry.lifecycle.currentState != Lifecycle.State.RESUMED) return
    if (!popBackStack(route, false)) go(route)
}
