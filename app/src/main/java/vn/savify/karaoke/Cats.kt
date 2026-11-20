package vn.savify.karaoke

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Mot the loai nhac: name hien tren TV, q la tu khoa tim kiem. custom = nguoi dung tu tao (xoa duoc). */
data class Cat(val name: String, val q: String, val custom: Boolean)

object Cats {
    private val DEFAULTS = listOf(
        Cat("Acoustic & Hòa Tấu", "nhạc acoustic hòa tấu", false),
        Cat("Saxophone Jazz", "saxophone jazz thư giãn", false),
        Cat("Chill & Lofi Beats", "chill lofi beats", false),
        Cat("Nhạc Trẻ & EDM", "nhạc trẻ edm remix", false),
        Cat("Trữ Tình Quê Hương", "nhạc trữ tình quê hương", false)
    )

    fun load(sp: SharedPreferences): MutableList<Cat> {
        val out = ArrayList<Cat>(DEFAULTS)
        try {
            val txt = sp.getString("cats", null) ?: return out
            val arr = JSONArray(txt)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val n = o.optString("n")
                val q = o.optString("q")
                if (n.isNotBlank() && q.isNotBlank()) out.add(Cat(n, q, true))
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun save(sp: SharedPreferences, list: List<Cat>) {
        val arr = JSONArray()
        list.filter { it.custom }.forEach { arr.put(JSONObject().put("n", it.name).put("q", it.q)) }
        sp.edit().putString("cats", arr.toString()).apply()
    }
}
