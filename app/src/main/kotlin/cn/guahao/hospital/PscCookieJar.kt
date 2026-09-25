package cn.guahao.hospital

import okhttp3.*

/** Per imported patient session. Domain/path/secure/expiry matching is delegated to OkHttp. */
class PscCookieJar(private val restore: () -> List<String>, private val persist: (List<String>) -> Unit,
    private val allowedHost: String = "psc.hkinfo.net") : CookieJar {
    private val cookies = mutableListOf<Cookie>()
    private var loaded = false
    private fun load(url: HttpUrl) {
        if (!loaded) { cookies += restore().mapNotNull { Cookie.parse(url, it) }.filter { it.domain == allowedHost }; loaded = true }
    }
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        require(url.host == allowedHost)
        load(url)
        cookies.filter { it.domain == allowedHost }.forEach { fresh ->
            this.cookies.removeAll { it.name == fresh.name && it.path == fresh.path && it.domain == fresh.domain }
            if (fresh.expiresAt > System.currentTimeMillis()) this.cookies += fresh
        }
        persist(this.cookies.map { it.toString() })
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (url.host != allowedHost) return emptyList()
        load(url)
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies.filter { it.matches(url) }
    }
}
