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

    /** Danh sach the loai mac dinh (dung cho nut "Khoi phuc the loai mac dinh"). */
    fun defaults(): MutableList<Cat> = ArrayList(DEFAULTS)

    /**
     * Tat ca the loai (ke ca 5 the loai co dinh) deu xoa duoc. Danh sach day du duoc luu o khoa "cats2";
     * neu chua co (ban cu) thi lay 5 the loai mac dinh + cac the loai tu tao da luu o khoa "cats".
     */
    fun load(sp: SharedPreferences): MutableList<Cat> {
        try {
            val txt2 = sp.getString("cats2", null)
            if (txt2 != null) {
                val out = ArrayList<Cat>()
                val arr = JSONArray(txt2)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val n = o.optString("n")
                    val q = o.optString("q")
                    if (n.isNotBlank() && q.isNotBlank()) out.add(Cat(n, q, o.optBoolean("c", true)))
                }
                return out
            }
        } catch (e: Exception) {
        }
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
        list.forEach { arr.put(JSONObject().put("n", it.name).put("q", it.q).put("c", it.custom)) }
        sp.edit().putString("cats2", arr.toString()).apply()
    }
}
