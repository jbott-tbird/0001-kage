// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.core.account

import java.net.URI

data class OAuthConfiguration(
    val clientId: String,
    val authorizationEndpoint: URI,
    val tokenEndpoint: URI,
    val redirectUri: URI,
    val scopes: List<String>,
)
