package com.kemo.calc;

import java.math.BigDecimal;
import java.math.MathContext;

/** Small recursive-descent calculator: + - × ÷ % ( ) and decimals. */
public final class Calc {
    private Calc() {}

    public static double eval(String s) {
        Parser p = new Parser(s);
        double v = p.expr();
        if (p.pos != p.s.length()) throw new ArithmeticException("syntax");
        if (Double.isNaN(v) || Double.isInfinite(v)) throw new ArithmeticException("range");
        return v;
    }

    /** Formats a result to at most 12 significant digits without float noise (0.1+0.2 -> 0.3). */
    public static String format(double v) {
        if (v == 0) return "0";
        double a = Math.abs(v);
        BigDecimal bd;
        if (a >= 1e15 || a < 1e-9) {
            bd = new BigDecimal(v).round(new MathContext(8)).stripTrailingZeros();
            return bd.toString().replace("E+", "E");
        }
        bd = new BigDecimal(v).round(new MathContext(12)).stripTrailingZeros();
        return bd.toPlainString();
    }

    private static final class Parser {
        final String s;
        int pos = 0;
        boolean pctFlag = false;   // last postfix chain ended with %
        boolean pctTerm = false;   // last term was a lone "n%"

        Parser(String s) { this.s = s; }

        char peek() { return pos < s.length() ? s.charAt(pos) : '\0'; }

        double expr() {
            double v = term();
            while (true) {
                char c = peek();
                if (c == '+' || c == '−' || c == '-') {
                    pos++;
                    double t = term();
                    if (pctTerm) t = v * t;          // 50 + 10%  ->  50 + 5
                    v = (c == '+') ? v + t : v - t;
                } else break;
            }
            return v;
        }

        double term() {
            boolean single = true;
            double v = unary();
            boolean lastPct = pctFlag;
            while (true) {
                char c = peek();
                if (c == '×' || c == '*') {
                    pos++; v *= unary(); single = false;
                } else if (c == '÷' || c == '/') {
                    pos++;
                    double r = unary();
                    if (r == 0) throw new ArithmeticException("div0");
                    v /= r; single = false;
                } else if (c == '(' || c == '.' || (c >= '0' && c <= '9')) {
                    v *= unary(); single = false;   // implicit multiplication: 2(3), (1)(2), 5%3
                } else break;
            }
            pctTerm = single && lastPct;
            return v;
        }

        double unary() {
            char c = peek();
            if (c == '−' || c == '-') { pos++; return -unary(); }
            if (c == '+') { pos++; return unary(); }
            return postfix();
        }

        double postfix() {
            double v = primary();
            pctFlag = false;
            while (peek() == '%') { pos++; v /= 100.0; pctFlag = true; }
            return v;
        }

        double primary() {
            char c = peek();
            if (c == '(') {
                pos++;
                double v = expr();
                if (peek() != ')') throw new ArithmeticException("paren");
                pos++;
                return v;
            }
            int st = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
            if (st == pos) throw new ArithmeticException("syntax");
            try {
                return Double.parseDouble(s.substring(st, pos));
            } catch (NumberFormatException e) {
                throw new ArithmeticException("number");
            }
        }
    }
}
