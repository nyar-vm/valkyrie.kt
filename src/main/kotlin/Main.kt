/*
 * Copyright (c) 2012, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.sl.launcher

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import java.io.*

object SLMain {
    private const val SL = "sl"

    /**
     * The main entry point.
     */
    @Throws(IOException::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val source: Source
        val options: MutableMap<String, String> = HashMap()
        var file: String? = null
        for (arg in args) {
            if (parseOption(options, arg)) {
                continue
            } else {
                if (file == null) {
                    file = arg
                }
            }
        }

        source = if (file == null) {
            // @formatter:off
            Source.newBuilder(SL, InputStreamReader(System.`in`), "<stdin>").build()
            // @formatter:on
        } else {
            Source.newBuilder(SL, File(file)).build()
        }

        System.exit(executeSource(source, System.`in`, System.out, options))
    }

    private fun executeSource(source: Source, `in`: InputStream, out: PrintStream, options: Map<String, String>): Int {
        val context: Context
        val err = System.err
        try {
            context = Context.newBuilder(SL).`in`(`in`).out(out).options(options).build()
        } catch (e: IllegalArgumentException) {
            err.println(e.message)
            return 1
        }
        out.println("== running on " + context.engine)

        try {
            val result = context.eval(source)
            if (context.getBindings(SL).getMember("main") == null) {
                err.println("No function main() defined in SL source file.")
                return 1
            }
            if (!result.isNull) {
                out.println(result.toString())
            }
            return 0
        } catch (ex: PolyglotException) {
            if (ex.isInternalError) {
                // for internal errors we print the full stack trace
                ex.printStackTrace()
            } else {
                err.println(ex.message)
            }
            return 1
        } finally {
            context.close()
        }
    }

    private fun parseOption(options: MutableMap<String, String>, arg: String): Boolean {
        if (arg.length <= 2 || !arg.startsWith("--")) {
            return false
        }
        val eqIdx = arg.indexOf('=')
        val key: String
        var value: String?
        if (eqIdx < 0) {
            key = arg.substring(2)
            value = null
        } else {
            key = arg.substring(2, eqIdx)
            value = arg.substring(eqIdx + 1)
        }

        if (value == null) {
            value = "true"
        }
        val index = key.indexOf('.')
        var group = key
        if (index >= 0) {
            group = group.substring(0, index)
        }
        options[key] = value
        return true
    }
}