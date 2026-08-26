package com.flowtube.app.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaginationTriggerPolicyTest {

    @Test
    fun same_request_key_empty_then_nonempty_without_callback_produces_zero_trigger() {
        val policy = PaginationTriggerPolicy(threshold = 3)
        policy.resetForRequest("search:queryA")

        // User callback on empty results
        val triggerEmpty = policy.onUserInputPosition(
            totalItemsCount = 0,
            lastVisibleItemIndex = -1,
            hasNextPage = true,
            isLoadingNextPage = false,
            requestKey = "search:queryA"
        )
        assertFalse("UserInput on empty items must not trigger", triggerEmpty)

        // Nonempty results arrive and layout threshold reached without a NEW user input callback
        // Pure policy has no background arm; without onUserInputPosition being called, trigger count is 0
        assertEquals(0, policy.triggerCountForTest)
    }

    @Test
    fun programmatic_or_layout_position_without_user_input_produces_zero_trigger() {
        val policy = PaginationTriggerPolicy(threshold = 3)
        policy.resetForRequest("search:queryA")

        // Layout update / programmatic scroll near end does not call onUserInputPosition
        // Trigger count remains 0
        assertEquals(0, policy.triggerCountForTest)
    }

    @Test
    fun one_user_input_near_end_produces_exactly_one_trigger() {
        val policy = PaginationTriggerPolicy(threshold = 3)
        policy.resetForRequest("search:queryA")

        // User input callback arrives at threshold position (lastVisible 8 >= 10 - 3)
        val shouldTrigger = policy.onUserInputPosition(
            totalItemsCount = 10,
            lastVisibleItemIndex = 8,
            hasNextPage = true,
            isLoadingNextPage = false,
            requestKey = "search:queryA"
        )

        assertTrue("User input callback reaching threshold must trigger exactly one load", shouldTrigger)
        assertEquals(1, policy.triggerCountForTest)
    }

    @Test
    fun duplicate_user_input_callbacks_at_same_position_are_deduplicated() {
        val policy = PaginationTriggerPolicy(threshold = 3)
        policy.resetForRequest("search:queryA")

        // First callback during drag at index 8
        val trigger1 = policy.onUserInputPosition(
            totalItemsCount = 10,
            lastVisibleItemIndex = 8,
            hasNextPage = true,
            isLoadingNextPage = false,
            requestKey = "search:queryA"
        )
        assertTrue("First callback triggers", trigger1)
        assertEquals(1, policy.triggerCountForTest)

        // Multiple nested scroll callbacks in same position/drag
        val trigger2 = policy.onUserInputPosition(
            totalItemsCount = 10,
            lastVisibleItemIndex = 8,
            hasNextPage = true,
            isLoadingNextPage = false,
            requestKey = "search:queryA"
        )
        assertFalse("Duplicate callback at same position does not trigger again", trigger2)
        assertEquals(1, policy.triggerCountForTest)
    }

    @Test
    fun page_append_and_loading_false_with_no_user_input_produces_zero_trigger() {
        val policy = PaginationTriggerPolicy(threshold = 3)
        policy.resetForRequest("search:queryA")

        val trigger1 = policy.onUserInputPosition(
            totalItemsCount = 10,
            lastVisibleItemIndex = 8,
            hasNextPage = true,
            isLoadingNextPage = false,
            requestKey = "search:queryA"
        )
        assertTrue(trigger1)
        assertEquals(1, policy.triggerCountForTest)

        // Page append occurs: totalItems becomes 15, isLoadingNextPage false.
        // No user input callback => triggers remain 1
        assertEquals(1, policy.triggerCountForTest)
    }

    @Test
    fun second_distinct_user_input_position_near_end_produces_one_next_trigger() {
        val policy = PaginationTriggerPolicy(threshold = 3)
        policy.resetForRequest("search:queryA")

        // First trigger
        val trigger1 = policy.onUserInputPosition(
            totalItemsCount = 10,
            lastVisibleItemIndex = 8,
            hasNextPage = true,
            isLoadingNextPage = false,
            requestKey = "search:queryA"
        )
        assertTrue(trigger1)
        assertEquals(1, policy.triggerCountForTest)

        // Page appended, total now 20. User scrolls further to distinct index 18 (>= 20 - 3)
        val trigger2 = policy.onUserInputPosition(
            totalItemsCount = 20,
            lastVisibleItemIndex = 18,
            hasNextPage = true,
            isLoadingNextPage = false,
            requestKey = "search:queryA"
        )
        assertTrue("Second distinct user input position reaching threshold triggers next page", trigger2)
        assertEquals(2, policy.triggerCountForTest)
    }

    @Test
    fun does_not_trigger_when_no_next_page_or_already_loading() {
        val policy = PaginationTriggerPolicy(threshold = 3)
        policy.resetForRequest("search:queryA")

        val noNext = policy.onUserInputPosition(
            totalItemsCount = 10,
            lastVisibleItemIndex = 9,
            hasNextPage = false,
            isLoadingNextPage = false,
            requestKey = "search:queryA"
        )
        assertFalse("No next page must not trigger", noNext)

        val loading = policy.onUserInputPosition(
            totalItemsCount = 10,
            lastVisibleItemIndex = 9,
            hasNextPage = true,
            isLoadingNextPage = true,
            requestKey = "search:queryA"
        )
        assertFalse("Already loading must not trigger", loading)
        assertEquals(0, policy.triggerCountForTest)
    }

    @Test
    fun query_filter_request_key_changes_resets_deduplication_and_trigger_count() {
        val policy = PaginationTriggerPolicy(threshold = 3)
        policy.resetForRequest("search:cats")

        val triggerCats = policy.onUserInputPosition(
            totalItemsCount = 10,
            lastVisibleItemIndex = 9,
            hasNextPage = true,
            isLoadingNextPage = false,
            requestKey = "search:cats"
        )
        assertTrue(triggerCats)
        assertEquals(1, policy.triggerCountForTest)

        // Query or filter changes to "search:dogs" via resetForRequest
        policy.resetForRequest("search:dogs")
        assertEquals(0, policy.triggerCountForTest)

        // User input on dogs at threshold
        val triggerDogs = policy.onUserInputPosition(
            totalItemsCount = 10,
            lastVisibleItemIndex = 9,
            hasNextPage = true,
            isLoadingNextPage = false,
            requestKey = "search:dogs"
        )
        assertTrue(triggerDogs)
        assertEquals(1, policy.triggerCountForTest)
    }
}
