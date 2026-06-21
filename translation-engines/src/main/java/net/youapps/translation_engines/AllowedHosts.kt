/*
 * Copyright (c) 2026 You Apps
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package net.youapps.translation_engines

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Global OkHttp allowlist. App.kt calls [configure] once at startup with the set of permitted
 * hosts. An empty set (the default) blocks every outbound request, which is the correct state for
 * offline builds where INTERNET permission is absent anyway.
 */
object AllowedHosts {
    @Volatile
    private var hosts: Set<String> = emptySet()

    fun configure(allowedHosts: Set<String>) {
        hosts = allowedHosts
    }

    /** Returns a snapshot of the current allowed hosts for use in interceptors. */
    fun snapshot(): Set<String> = hosts
}

/**
 * OkHttp interceptor that rejects any request whose host is not in [AllowedHosts].
 * An empty allowlist blocks all requests.
 */
class AllowedHostsInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        val allowed = AllowedHosts.snapshot()
        if (!allowed.contains(host) && allowed.none { host.endsWith(".$it") }) {
            throw IOException("Outbound request to '$host' is not in the allowed hosts list")
        }
        return chain.proceed(chain.request())
    }
}
