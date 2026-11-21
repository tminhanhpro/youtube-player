package vn.savify.karaoke

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Danh sach bai yeu thich (luu tren TV, khoa "favs"). Bai moi them nam tren cung. */
object Favs {
    private const val MAX = 300

    fun load(sp: SharedPreferences): MutableList<SearchHit> {
        val out = ArrayList<SearchHit>()
        try {
            val arr = JSONArray(sp.getString("favs", null) ?: return out)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id")
                if (id.isNotBlank() && out.none { it.videoId == id }) {
                    out.add(SearchHit(id, o.optString("t"), o.optString("c"), o.optInt("d", 0)))
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun save(sp: SharedPreferences, list: List<SearchHit>) {
        val arr = JSONArray()
        list.take(MAX).forEach {
            arr.put(JSONObject().put("id", it.videoId).put("t", it.title).put("c", it.channel).put("d", it.durationSec))
        }
        sp.edit().putString("favs", arr.toString()).apply()
    }
}
