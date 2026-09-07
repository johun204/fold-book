package com.foldbook

/**
 * 파일명을 사람이 기대하는 순서로 비교한다.
 * 예: 2.jpg < 9.jpg < 10.jpg (1 때문에 꼬이지 않음), 알파벳이 섞여도 숫자 덩어리는 값으로 비교.
 * 선행 0 은 무시하되(008 == 8), 자릿수가 다르면 큰 수가 뒤로 간다.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val s = a.lowercase()
        val t = b.lowercase()
        var i = 0
        var j = 0
        while (i < s.length && j < t.length) {
            val cs = s[i]
            val ct = t[j]
            if (cs.isDigit() && ct.isDigit()) {
                var iEnd = i
                while (iEnd < s.length && s[iEnd].isDigit()) iEnd++
                var jEnd = j
                while (jEnd < t.length && t[jEnd].isDigit()) jEnd++
                val ns = s.substring(i, iEnd).trimStart('0')
                val nt = t.substring(j, jEnd).trimStart('0')
                if (ns.length != nt.length) return ns.length - nt.length
                val c = ns.compareTo(nt)
                if (c != 0) return c
                i = iEnd
                j = jEnd
            } else {
                if (cs != ct) return cs.code - ct.code
                i++
                j++
            }
        }
        return (s.length - i) - (t.length - j)
    }
}
