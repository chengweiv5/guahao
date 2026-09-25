package cn.guahao.storage

/** Values remain encrypted by the production implementation. */
interface SecretStore {
    fun read(name: String): String?
    fun write(name: String, text: String)
}
