package valkyrie.language

import com.oracle.truffle.api.*
import com.oracle.truffle.api.debug.DebuggerTags
import com.oracle.truffle.api.dsl.NodeFactory
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.instrumentation.AllocationReporter
import com.oracle.truffle.api.instrumentation.ProvidedTags
import com.oracle.truffle.api.instrumentation.StandardTags
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.NodeInfo
import com.oracle.truffle.api.nodes.RootNode
import com.oracle.truffle.api.`object`.Shape
import com.oracle.truffle.api.source.Source
import com.oracle.truffle.api.strings.TruffleString
import com.oracle.truffle.sl.builtins.SLBuiltinNode
import com.oracle.truffle.sl.nodes.SLEvalRootNode
import com.oracle.truffle.sl.nodes.SLExpressionNode
import com.oracle.truffle.sl.nodes.SLRootNode
import com.oracle.truffle.sl.nodes.SLUndefinedFunctionRootNode
import com.oracle.truffle.sl.nodes.local.SLReadArgumentNode
import com.oracle.truffle.sl.parser.SimpleLanguageParser
import com.oracle.truffle.sl.runtime.SLContext
import valkyrie.language.file_type.ValkyrieFileDetector
import valkyrie.runtime.SLLanguageView
import valkyrie.runtime.ValkyrieObject
import valkyrie.runtime.ValkyrieString
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile

/**
 * SL is a simple language to demonstrate and showcase features of Truffle. The implementation is as
 * simple and clean as possible in order to help understanding the ideas and concepts of Truffle.
 * The language has first class functions, and objects are key-value stores.
 *
 *
 * SL is dynamically typed, i.e., there are no type names specified by the programmer. SL is
 * strongly typed, i.e., there is no automatic conversion between types. If an operation is not
 * available for the types encountered at run time, a type error is reported and execution is
 * stopped. For example, `4 - "2"` results in a type error because subtraction is only defined
 * for numbers.
 *
 *
 *
 * **Types:**
 *
 *  * Number: arbitrary precision integer numbers. The implementation uses the Java primitive type
 * `long` to represent numbers that fit into the 64 bit range, and [SLBigInteger] for
 * numbers that exceed the range. Using a primitive type such as `long` is crucial for
 * performance.
 *  * Boolean: implemented as the Java primitive type `boolean`.
 *  * String: implemented as the Java standard type [String].
 *  * Function: implementation type [SLFunction].
 *  * Object: efficient implementation using the object model provided by Truffle. The
 * implementation type of objects is a subclass of [DynamicObject].
 *  * Null (with only one value `null`): implemented as the singleton
 * [SLNull.SINGLETON].
 *
 * The class [SLTypes] lists these types for the Truffle DSL, i.e., for type-specialized
 * operations that are specified using Truffle DSL annotations.
 *
 *
 *
 * **Language concepts:**
 *
 *  * Literals for [numbers][SLBigIntegerLiteralNode] , [strings][SLStringLiteralNode],
 * and [functions][SLFunctionLiteralNode].
 *  * Basic arithmetic, logical, and comparison operations: [+][SLAddNode], [ -][SLSubNode], [*][SLMulNode], [/][SLDivNode], [logical and][SLLogicalAndNode],
 * [logical or][SLLogicalOrNode], [==][SLEqualNode], !=, [&amp;lt;][SLLessThanNode],
 * [&amp;le;][SLLessOrEqualNode], &gt;, .
 *  * Local variables: local variables must be defined (via a [ write][SLWriteLocalVariableNode]) before they can be used (by a [read][SLReadLocalVariableNode]). Local variables are
 * not visible outside of the block where they were first defined.
 *  * Basic control flow statements: [blocks][SLBlockNode], [if][SLIfNode],
 * [while][SLWhileNode] with [break][SLBreakNode] and [continue][SLContinueNode],
 * [return][SLReturnNode].
 *  * Debugging control: [debugger][SLDebuggerNode] statement uses
 * [DebuggerTags.AlwaysHalt] tag to halt the execution when run under the debugger.
 *  * Function calls: [invocations][SLInvokeNode] are efficiently implemented with
 * [polymorphic inline caches][SLDispatchNode].
 *  * Object access: [SLReadPropertyNode] and [SLWritePropertyNode] use a cached
 * [DynamicObjectLibrary] as the polymorphic inline cache for property reads and writes,
 * respectively.
 *
 *
 *
 *
 * **Syntax and parsing:**<br></br>
 * The syntax is described as an attributed grammar. The [SimpleLanguageParser] and
 * [SimpleLanguageLexer] are automatically generated by ANTLR 4. The grammar contains semantic
 * actions that build the AST for a method. To keep these semantic actions short, they are mostly
 * calls to the [SLNodeFactory] that performs the actual node creation. All functions found in
 * the SL source are added to the [SLFunctionRegistry], which is accessible from the
 * [SLContext].
 *
 *
 *
 * **Builtin functions:**<br></br>
 * Library functions that are available to every SL source without prior definition are called
 * builtin functions. They are added to the [SLFunctionRegistry] when the [SLContext] is
 * created. Some of the current builtin functions are
 *
 *  * [readln][SLReadlnBuiltin]: Read a String from the [standard][SLContext.getInput].
 *  * [println][SLPrintlnBuiltin]: Write a value to the [standard][SLContext.getOutput].
 *  * [nanoTime][SLNanoTimeBuiltin]: Returns the value of a high-resolution time, in
 * nanoseconds.
 *  * [defineFunction][SLDefineFunctionBuiltin]: Parses the functions provided as a String
 * argument and adds them to the function registry. Functions that are already defined are replaced
 * with the new version.
 *  * [stckTrace][SLStackTraceBuiltin]: Print all function activations with all local
 * variables.
 *
 */
@TruffleLanguage.Registration(
    id = ValkyrieLanguage.ID,
    name = ValkyrieLanguage.DisplayName,
    defaultMimeType = ValkyrieLanguage.MIME_TYPE,
    characterMimeTypes = [ValkyrieLanguage.MIME_TYPE],
    contextPolicy = TruffleLanguage.ContextPolicy.SHARED,
    fileTypeDetectors = [ValkyrieFileDetector::class],
    website = "https://www.graalvm.org/graalvm-as-a-platform/implement-language/"
)
@ProvidedTags(
    StandardTags.CallTag::class,
    StandardTags.StatementTag::class,
    StandardTags.RootTag::class,
    StandardTags.RootBodyTag::class,
    StandardTags.ExpressionTag::class,
    DebuggerTags.AlwaysHalt::class,
    StandardTags.ReadVariableTag::class,
    StandardTags.WriteVariableTag::class
)
class ValkyrieLanguage : TruffleLanguage<SLContext>() {
    private val singleContext: Assumption = Truffle.getRuntime().createAssumption("Single SL context.")

    private val builtinTargets: MutableMap<NodeFactory<out SLBuiltinNode>, RootCallTarget> = ConcurrentHashMap()
    private val undefinedFunctions: MutableMap<TruffleString, RootCallTarget?> = ConcurrentHashMap()

    val rootShape: Shape

    override fun createContext(env: Env): SLContext {
        return SLContext(this, env, ArrayList(EXTERNAL_BUILTINS))
    }

    override fun patchContext(context: SLContext, newEnv: Env): Boolean {
        context.patchContext(newEnv)
        return true
    }

    fun getOrCreateUndefinedFunction(name: TruffleString): RootCallTarget? {
        var target = undefinedFunctions[name]
        if (target == null) {
            target = SLUndefinedFunctionRootNode(this, name).getCallTarget()
            val other = undefinedFunctions.putIfAbsent(name, target)
            if (other != null) {
                target = other
            }
        }
        return target
    }

    fun lookupBuiltin(factory: NodeFactory<out SLBuiltinNode>): RootCallTarget {
        val target = builtinTargets[factory]
        if (target != null) {
            return target
        }

        /*
         * The builtin node factory is a class that is automatically generated by the Truffle DSL.
         * The signature returned by the factory reflects the signature of the @Specialization
         *
         * methods in the builtin classes.
         */
        val argumentCount = factory.executionSignature.size
        val argumentNodes = arrayOfNulls<SLExpressionNode>(argumentCount)
        /*
         * Builtin functions are like normal functions, i.e., the arguments are passed in as an
         * Object[] array encapsulated in SLArguments. A SLReadArgumentNode extracts a parameter
         * from this array.
         */for (i in 0 until argumentCount) {
            argumentNodes[i] = SLReadArgumentNode(i)
        }
        /* Instantiate the builtin node. This node performs the actual functionality. */
        val builtinBodyNode = factory.createNode(argumentNodes as Any)
        builtinBodyNode.addRootTag()
        /* The name of the builtin function is specified via an annotation on the node class. */
        val name = ValkyrieString.fromJavaString(
            lookupNodeInfo(builtinBodyNode.javaClass)!!.shortName
        )
        builtinBodyNode.setUnavailableSourceSection()

        /* Wrap the builtin in a RootNode. Truffle requires all AST to start with a RootNode. */
        val rootNode =
            SLRootNode(this, FrameDescriptor(), builtinBodyNode, BUILTIN_SOURCE.createUnavailableSection(), name)

        /*
         * Register the builtin function in the builtin registry. Call targets for builtins may be
         * reused across multiple contexts.
         */
        val newTarget = rootNode.getCallTarget()
        val oldTarget = builtinTargets.putIfAbsent(factory, newTarget)
        if (oldTarget != null) {
            return oldTarget
        }
        return newTarget
    }

    @Throws(Exception::class)
    override fun parse(request: ParsingRequest): CallTarget {
        val source = request.getSource()
        val functions: Map<TruffleString, RootCallTarget>
        /*
         * Parse the provided source. At this point, we do not have a SLContext yet. Registration of
         * the functions with the SLContext happens lazily in SLEvalRootNode.
         */if (request.getArgumentNames().isEmpty()) {
            functions = SimpleLanguageParser.parseSL(this, source)
        } else {
            val sb = StringBuilder()
            sb.append("function main(")
            var sep = ""
            for (argumentName in request.getArgumentNames()) {
                sb.append(sep)
                sb.append(argumentName)
                sep = ","
            }
            sb.append(") { return ")
            sb.append(source.characters)
            sb.append(";}")
            val language = if (source.language == null) ID else source.language
            val decoratedSource = Source.newBuilder(language, sb.toString(), source.name).build()
            functions = SimpleLanguageParser.parseSL(this, decoratedSource)
        }

        val main = functions[ValkyrieString.MAIN]
        val evalMain: RootNode = if (main != null) {
            /*
             * We have a main function, so "evaluating" the parsed source means invoking that main
             * function. However, we need to lazily register functions into the SLContext first, so
             * we cannot use the original SLRootNode for the main function. Instead, we create a new
             * SLEvalRootNode that does everything we need.
             */
            SLEvalRootNode(this, main, functions)
        } else {
            /*
             * Even without a main function, "evaluating" the parsed source needs to register the
             * functions into the SLContext.
             */
            SLEvalRootNode(this, null, functions)
        }
        return evalMain.getCallTarget()
    }

    /**
     * SLLanguage specifies the [ContextPolicy.SHARED] in
     * [Registration.contextPolicy]. This means that a single [TruffleLanguage]
     * instance can be reused for multiple language contexts. Before this happens the Truffle
     * framework notifies the language by invoking [.initializeMultipleContexts]. This
     * allows the language to invalidate certain assumptions taken for the single context case. One
     * assumption SL takes for single context case is located in [SLEvalRootNode]. There
     * functions are only tried to be registered once in the single context case, but produce a
     * boundary call in the multi context case, as function registration is expected to happen more
     * than once.
     *
     *
     * Value identity caches should be avoided and invalidated for the multiple contexts case as no
     * value will be the same. Instead, in multi context case, a language should only use types,
     * shapes and code to speculate.
     *
     *
     * For a new language it is recommended to start with [ContextPolicy.EXCLUSIVE] and as the
     * language gets more mature switch to [ContextPolicy.SHARED].
     */
    override fun initializeMultipleContexts() {
        singleContext.invalidate()
    }

    fun isSingleContext(): Boolean {
        return singleContext.isValid
    }

    public override fun getLanguageView(context: SLContext, value: Any): Any {
        return SLLanguageView.create(value)
    }

    override fun isVisible(context: SLContext, value: Any): Boolean {
        return !InteropLibrary.getFactory().getUncached(value).isNull(value)
    }

    override fun getScope(context: SLContext): Any {
        return context.functionRegistry.functionsObject
    }

    /**
     * Allocate an empty object. All new objects initially have no properties. Properties are added
     * when they are first stored, i.e., the store triggers a shape change of the object.
     */
    fun createObject(reporter: AllocationReporter): ValkyrieObject {
        reporter.onEnter(null, 0, AllocationReporter.SIZE_UNKNOWN)
        val `object` = ValkyrieObject(rootShape)
        reporter.onReturnValue(`object`, 0, AllocationReporter.SIZE_UNKNOWN)
        return `object`
    }

    init {
        counter++
        rootShape = Shape.newBuilder().layout(ValkyrieObject::class.java).build()
    }

    override fun exitContext(context: SLContext, exitMode: ExitMode, exitCode: Int) {
        /*
         * Runs shutdown hooks during explicit exit triggered by TruffleContext#closeExit(Node, int)
         * or natural exit triggered during natural context close.
         */
        context.runShutdownHooks()
    }

    companion object {
        @JvmField
        @Volatile
        var counter: Int = 0

        const val ID = "valkyrie";
        const val DisplayName = "Valkyrie Language"
        const val MIME_TYPE: String = "application/x-valkyrie"
        private val BUILTIN_SOURCE: Source = Source.newBuilder(ID, "", "SL builtin").build()

        @JvmField
        val STRING_ENCODING: TruffleString.Encoding = TruffleString.Encoding.UTF_16

        @JvmStatic
        fun lookupNodeInfo(clazz: Class<*>?): NodeInfo? {
            if (clazz == null) {
                return null
            }
            val info = clazz.getAnnotation(
                NodeInfo::class.java
            )
            return info ?: lookupNodeInfo(clazz.superclass)
        }

        private val REFERENCE: LanguageReference<ValkyrieLanguage> = LanguageReference.create(
            ValkyrieLanguage::class.java
        )

        @JvmStatic
        fun get(node: Node?): ValkyrieLanguage {
            return REFERENCE[node]
        }

        private val EXTERNAL_BUILTINS: MutableList<NodeFactory<out SLBuiltinNode>> = Collections.synchronizedList(
            ArrayList()
        )

        @JvmStatic
        fun installBuiltin(builtin: NodeFactory<out SLBuiltinNode>) {
            EXTERNAL_BUILTINS.add(builtin)
        }
    }
}
