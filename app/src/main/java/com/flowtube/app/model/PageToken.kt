package com.flowtube.app.model

sealed interface PageToken {
    data class Id(val id: String) : PageToken
    data class Url(val url: String) : PageToken
}

