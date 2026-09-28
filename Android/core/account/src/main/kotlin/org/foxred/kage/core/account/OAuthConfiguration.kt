package org.foxred.kage.core.account

import java.net.URI

data class OAuthConfiguration(
    val clientId: String,
    val authorizationEndpoint: URI,
    val tokenEndpoint: URI,
    val redirectUri: URI,
    val scopes: List<String>,
)
