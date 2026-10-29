package com.wenqu.app

import java.text.DecimalFormat

/** 本地四则/幂/根/百分比计算器（递归下降），识别 "3+5*2"、"√144"、"20% of 50" 之类 */
object MathEval {

    fun tryEval(input: String): Pair<String, String>? {
        var s = input.trim()
        if (!s.any { it.isDigit() }) return null
        s = s.replace("×", "*").replace("x", "*").replace("X", "*")
            .replace("÷", "/").replace("＋", "+").replace("－", "-").replace(" ", "")
        if (!Regex("^[0-9+\\-*/^().%√]+$").matches(s)) return null
        // 必须含二元运算
        if (!Regex("[0-9)]\\s*[+*/^%]").containsMatchIn(s) && !Regex("[0-9)]\\s*-[0-9(]").containsMatchIn(s)) return null
        return try {
            val v = Parser(s).parse()
            if (v.isNaN() || v.isInfinite()) null else s to fmt(v)
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
