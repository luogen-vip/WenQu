package com.wenqu.app

import java.text.DecimalFormat

/** 本地四则/幂/根/百分比计算器（递归下降），识别 "3+5*2"、"√144"、"20% of 50" 之类 */
object MathEval {

    fun tryEval(input: String): Pair<String, String>? {
        var s = input.trim()
        if (!s.any { it.isDigit() } && !Regex("[一二两三四五六七八九十]").containsMatchIn(s)) return null
        // 中文口语运算符/数字归一化
        s = s.replace("×", "*").replace("x", "*").replace("X", "*")
            .replace("÷", "/").replace("＋", "+").replace("－", "-")
            .replace("乘以", "*").replace("乘", "*")
            .replace("除以", "/").replace("除", "/")
            .replace("加", "+").replace("减", "-")
            .replace(" ", "")
        s = Regex("[一二两三四五六七八九十零]+").replace(s) { m -> Nlp.chineseNum(m.value).toString() }
        // 从句子中抠出最长算式片段（支持「1+1等于多少」「算一下128*64+256」）
        val seg = Regex("[0-9+\\-*/^().%√]+").findAll(s)
            .filter { m ->
                m.value.length >= 3 && m.value.any { "+-*/^%√".contains(it) } &&
                        // 防止日期/年龄被误算：片段紧贴“年/月/日/号/岁”时跳过
                        !(m.range.first > 0 && s[m.range.first - 1] in "年月日号岁") &&
                        !(m.range.last + 1 < s.length && s[m.range.last + 1] in "年月日号岁")
            }
            .maxByOrNull { it.value.length } ?: return null
        val expr = seg.value.trim('.', '，', ',')
        if (!Regex("[0-9)]\\s*[+*/^%]").containsMatchIn(expr) && !Regex("[0-9)]\\s*-[0-9(]").containsMatchIn(expr)) return null
        return try {
            val v = Parser(expr).parse()
            if (v.isNaN() || v.isInfinite()) null else expr to fmt(v)
        } catch (e: Exception) { null }
    }

    private class Parser(val s: String) {
        var i = 0
        fun parse(): Double {
            val v = expr()
            if (i < s.length) throw IllegalStateException("残留在${s.substring(i)}")
            return v
        }
        fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]; val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }
        fun term(): Double {
            var v = pow()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) {
                val op = s[i++]; val r = pow()
                v = if (op == '*') v * r else v / r
            }
            return v
        }
        fun pow(): Double {
            val b = unary()
            if (i < s.length && s[i] == '^') { i++; return Math.pow(b, pow()) }
            return b
        }
        fun unary(): Double {
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            if (i < s.length && s[i] == '+') { i++; return unary() }
            if (i < s.length && s[i] == '√') { i++; return Math.sqrt(unary()) }
            return primary()
        }
        fun primary(): Double {
            if (i < s.length && s[i] == '(') {
                i++
                val v = expr()
                if (i < s.length && s[i] == ')') i++
                return v
            }
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (st == i) throw IllegalStateException("非法位置${if (i < s.length) s[i].toString() else "末尾"}")
            var v = s.substring(st, i).toDouble()
            if (i < s.length && s[i] == '%') { i++; v /= 100.0 }
            return v
        }
    }

    fun fmt(v: Double): String {
        return if (v == Math.floor(v) && Math.abs(v) < 1e15) {
            DecimalFormat("#,###").format(v.toLong())
        } else {
            DecimalFormat("#.######").format(v)
        }
    }

    fun answerText(expr: String, result: String): String {
        val openers = listOf(
            "这个我心算就行：",
            "小渠掐指一算：",
            "答案来了："
        )
        return "${openers.random()}\n\n$expr = $result\n\n还有别的要算的尽管丢过来，开根号、乘方、百分比都行。"
    }
}
