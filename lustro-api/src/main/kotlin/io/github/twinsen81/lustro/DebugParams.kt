package io.github.twinsen81.lustro

/**
 * Declares the query parameters of a route ([DebugRoute.query]), and is the base of its body
 * fields ([DebugRoute.body]). Each function adds one parameter and returns its handle, which
 * the handler gives to [DebugCall.get].
 *
 * A parameter is optional, so its value is `null` when the request does not give it, until
 * [DebugParam.required] or [DebugParam.withDefault] says otherwise. In the query or a form,
 * an empty value of an int, long, or boolean parameter takes the parameter's default, and
 * without a default it gets a `400`, as other text that is not a number or `true` or `false`
 * does: a request such as `?since=` usually comes from a variable that has no value. An empty
 * string is a string, which [DebugStringSpec.minLength] and [DebugStringSpec.values] can
 * refuse. In a JSON body, `null` counts as absent, and the JSON type must match: a string
 * parameter takes a JSON string, an int or long parameter a whole number, and a boolean
 * parameter `true` or `false`.
 */
@ExperimentalDebugRoutes
public open class DebugParams internal constructor(
    internal val route: DebugRoute,
    internal val location: ParamIn,
) {
    /** Declares a string parameter named [name]. */
    public fun string(name: String, declare: DebugStringSpec.() -> Unit = {}): DebugParam<String?> =
        DebugParam(add(name, ParamType.STRING, DebugStringSpec().apply(declare)))

    /**
     * Declares a string parameter named [name] that the request can give more than once, such
     * as `?ids=a&ids=b`: its value is every value that the request gives, in order, and each
     * one must fit the options of [declare]. In a JSON body, the field is an array of strings.
     */
    public fun strings(name: String, declare: DebugStringSpec.() -> Unit = {}): DebugParam<List<String>?> =
        DebugParam(add(name, ParamType.STRINGS, DebugStringSpec().apply(declare)))

    /**
     * Declares a whole-number parameter named [name], from [Int.MIN_VALUE] to [Int.MAX_VALUE]
     * unless [DebugIntegerSpec.min] and [DebugIntegerSpec.max] narrow it.
     */
    public fun int(name: String, declare: DebugIntegerSpec.() -> Unit = {}): DebugParam<Int?> =
        DebugParam(add(name, ParamType.INT, DebugIntegerSpec().apply(declare)))

    /** Declares a whole-number parameter named [name], as a [Long]. */
    public fun long(name: String, declare: DebugIntegerSpec.() -> Unit = {}): DebugParam<Long?> =
        DebugParam(add(name, ParamType.LONG, DebugIntegerSpec().apply(declare)))

    /** Declares a parameter named [name] that is `true` or `false`. */
    public fun boolean(name: String, declare: DebugParamSpec.() -> Unit = {}): DebugParam<Boolean?> =
        DebugParam(add(name, ParamType.BOOLEAN, DebugParamSpec().apply(declare)))

    internal fun add(name: String, type: ParamType, spec: DebugParamSpec): ParamDecl {
        route.checkOpen()
        check(name.isNotEmpty()) { "${route.describe()}: a parameter needs a name" }
        check(route.params.none { it.location == location && it.name == name }) {
            "${route.describe()}: two ${location.wire} parameters named $name"
        }
        return ParamDecl(route, name, location, type, spec).also { route.params += it }
    }
}

/**
 * Declares the fields of a route's request body ([DebugRoute.body]): the members of a JSON
 * object, or the fields of a form when [DebugRoute.acceptsForm] is on.
 */
@ExperimentalDebugRoutes
public class DebugBodyParams internal constructor(route: DebugRoute) : DebugParams(route, ParamIn.BODY) {
    /**
     * Declares a field named [name] that takes any JSON value. The handler gets the value as
     * JSON text, such as `["a","b"]` or `"text"`, and parses it itself. Set
     * [DebugParamSpec.schema] to describe the value in the OpenAPI document.
     */
    public fun json(name: String, declare: DebugParamSpec.() -> Unit = {}): DebugParam<String?> =
        DebugParam(add(name, ParamType.JSON, DebugParamSpec().apply(declare)))
}

/**
 * Declares the parameters in a route's path ([DebugRoute.path]): one for each segment in
 * braces, with the name in the braces. A path parameter is always present, so its value is
 * never `null`. A segment that the parameter does not take, such as `x` for an int, or a
 * value that is not one of [DebugStringSpec.values], matches no route: the path names a
 * resource that does not exist, so the client gets `404`, not `400`.
 */
@ExperimentalDebugRoutes
public class DebugPathParams internal constructor(private val params: DebugParams) {
    internal constructor(route: DebugRoute) : this(DebugParams(route, ParamIn.PATH))

    /** Declares the path parameter `{name}` as a string. */
    public fun string(name: String, declare: DebugStringSpec.() -> Unit = {}): DebugParam<String> =
        DebugParam(params.add(name, ParamType.STRING, DebugStringSpec().apply(declare)).apply { required = true })

    /** Declares the path parameter `{name}` as a whole number, from [Int.MIN_VALUE] to [Int.MAX_VALUE] unless narrowed. */
    public fun int(name: String, declare: DebugIntegerSpec.() -> Unit = {}): DebugParam<Int> =
        DebugParam(params.add(name, ParamType.INT, DebugIntegerSpec().apply(declare)).apply { required = true })

    /** Declares the path parameter `{name}` as a whole number, as a [Long]. */
    public fun long(name: String, declare: DebugIntegerSpec.() -> Unit = {}): DebugParam<Long> =
        DebugParam(params.add(name, ParamType.LONG, DebugIntegerSpec().apply(declare)).apply { required = true })
}

/**
 * The handle of a declared parameter. The handler reads the parameter's value with
 * [DebugCall.get]: `call[param]`. [T] is nullable for an optional parameter.
 */
@ExperimentalDebugRoutes
public class DebugParam<T> internal constructor(internal val decl: ParamDecl) {
    /** The parameter's name. */
    public val name: String get() = decl.name

    /**
     * Makes the parameter required, and returns its handle with a type that is not nullable.
     * A request without it gets a `400` with the parameter's name as `field`.
     */
    public fun required(): DebugParam<T & Any> {
        decl.route.checkOpen()
        check(!decl.hasDefault) { "${decl.route.describe()}: ${decl.name} has a default, so it can't be required" }
        decl.required = true
        @Suppress("UNCHECKED_CAST")
        return this as DebugParam<T & Any>
    }

    /**
     * Gives the parameter the value [value] when the request does not give it, and returns its
     * handle with a type that is not nullable. The OpenAPI document shows it as the default.
     */
    public fun withDefault(value: T & Any): DebugParam<T & Any> {
        decl.route.checkOpen()
        check(!decl.required) { "${decl.route.describe()}: ${decl.name} is required, so it can't have a default" }
        decl.default = value
        decl.hasDefault = true
        @Suppress("UNCHECKED_CAST")
        return this as DebugParam<T & Any>
    }
}

/** What a parameter declaration can say about any parameter. */
@ExperimentalDebugRoutes
@RoutesDsl
public open class DebugParamSpec internal constructor() {
    /** A description of the parameter, for the OpenAPI document. */
    public var description: String? = null

    /**
     * A JSON Schema that describes the parameter in the OpenAPI document in place of the one
     * made from its type and options, such as `{"const": true}` or [DebugRoutes.ref]. The
     * checks of a request still use the declared type and options.
     */
    public var schema: String? = null
}

/** The options of a string parameter. */
@ExperimentalDebugRoutes
public class DebugStringSpec internal constructor() : DebugParamSpec() {
    /** The shortest value that the parameter takes. `1` refuses an empty string. */
    public var minLength: Int = 0

    /** The longest value that the parameter takes, or `null` for no limit. */
    public var maxLength: Int? = null

    /** The values that the parameter takes, or `null` for any. */
    public var values: List<String>? = null
}

/** The options of a whole-number parameter. */
@ExperimentalDebugRoutes
public class DebugIntegerSpec internal constructor() : DebugParamSpec() {
    /** The smallest value that the parameter takes, or `null` for the type's smallest. */
    public var min: Long? = null

    /** The largest value that the parameter takes, or `null` for the type's largest. */
    public var max: Long? = null
}

internal enum class ParamIn(val wire: String) {
    PATH("path"),
    QUERY("query"),
    BODY("body"),
}

internal enum class ParamType {
    STRING,
    STRINGS,
    INT,
    LONG,
    BOOLEAN,
    JSON,
}

@OptIn(ExperimentalDebugRoutes::class)
internal class ParamDecl(
    val route: DebugRoute,
    val name: String,
    val location: ParamIn,
    val type: ParamType,
    val spec: DebugParamSpec,
) {
    var required: Boolean = false
    var default: Any? = null
    var hasDefault: Boolean = false
}
