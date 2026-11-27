package dev.lework.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

internal data class DetectedCodeLanguage(
    val id: String,
    val displayName: String,
)

private const val MAX_HIGHLIGHT_CHARS = 300_000
private const val MAX_HIGHLIGHT_LINE_LENGTH = 8_000

private object SyntaxStyles {
    val Plain = SpanStyle(color = Everforest.Fg)
    val Keyword = SpanStyle(color = Everforest.Red, fontWeight = FontWeight.Medium)
    val Operator = SpanStyle(color = Everforest.Orange)
    val Directive = SpanStyle(color = Everforest.Orange, fontWeight = FontWeight.Medium)
    val Type = SpanStyle(color = Everforest.Yellow)
    val Function = SpanStyle(color = Everforest.Green)
    val StringLit = SpanStyle(color = Everforest.Aqua)
    val Escape = SpanStyle(color = Everforest.Purple)
    val Number = SpanStyle(color = Everforest.Purple)
    val Literal = SpanStyle(color = Everforest.Purple, fontWeight = FontWeight.Medium)
    val Property = SpanStyle(color = Everforest.Blue)
    val KeyBold = SpanStyle(color = Everforest.Blue, fontWeight = FontWeight.Medium)
    val Comment = SpanStyle(color = Everforest.CommentGrey, fontStyle = FontStyle.Italic)
    val Punctuation = SpanStyle(color = Everforest.Grey2)
    val Heading = SpanStyle(color = Everforest.BoldGold, fontWeight = FontWeight.SemiBold)
    val DiffAdd = SpanStyle(color = Everforest.Green)
    val DiffDel = SpanStyle(color = Everforest.Red)
    val DiffHunk = SpanStyle(color = Everforest.Blue)
}

private enum class BacktickKind {
    NONE,
    RAW_STRING,
    TEMPLATE_STRING,
    IDENTIFIER,
}

private enum class BlockMode {
    NONE,
    BLOCK_COMMENT,
    LUA_BLOCK_COMMENT,
    HTML_COMMENT,
    TRIPLE_DOUBLE_STRING,
    TRIPLE_SINGLE_STRING,
    BACKTICK_STRING,
    RAW_HASH_STRING,
}

private enum class MarkupEmbeddedMode {
    NONE,
    SCRIPT,
    STYLE,
}

private class LexerState(
    var blockMode: BlockMode = BlockMode.NONE,
    var rawHashes: Int = 0,
    var inMarkupTag: Boolean = false,
    var markupEmbedded: MarkupEmbeddedMode = MarkupEmbeddedMode.NONE,
    var inMarkdownFence: Boolean = false,
    var markdownFenceLang: String = "",
    var markdownFenceDelim: String = "",
)

private data class LanguageSpec(
    val keywords: Set<String>,
    val typeKeywords: Set<String> = emptySet(),
    val literals: Set<String> = COMMON_LITERALS,
    val builtins: Set<String> = emptySet(),
    val slashSlashComment: Boolean = true,
    val hashComment: Boolean = false,
    val dashDashComment: Boolean = false,
    val blockComment: Boolean = true,
    val luaBlockComment: Boolean = false,
    val tripleDoubleQuote: Boolean = false,
    val tripleSingleQuote: Boolean = false,
    val singleQuoteString: Boolean = true,
    val rustSingleQuote: Boolean = false,
    val rustRawString: Boolean = false,
    val rustAttribute: Boolean = false,
    val pythonStringPrefix: Boolean = false,
    val backtickKind: BacktickKind = BacktickKind.NONE,
    val hashDirective: Boolean = false,
    val atAnnotation: Boolean = false,
    val dollarVar: Boolean = false,
    val caseInsensitive: Boolean = false,
    val pascalCaseTypes: Boolean = true,
    val sqlDoubleSingleQuoteEscape: Boolean = false,
)

private val COMMON_LITERALS = setOf(
    "true", "false", "null", "nil", "None", "True", "False",
    "undefined", "NaN", "Infinity", "this", "self", "super", "Self",
)

private val KOTLIN_SPEC = LanguageSpec(
    keywords = setOf(
        "package", "import", "class", "interface", "object", "fun", "val", "var", "const",
        "typealias", "if", "else", "when", "for", "while", "do", "return", "break", "continue",
        "throw", "try", "catch", "finally", "in", "is", "as", "by", "where", "init", "constructor",
        "companion", "data", "sealed", "enum", "annotation", "value", "inline", "noinline",
        "crossinline", "reified", "suspend", "tailrec", "operator", "infix", "override", "open",
        "final", "abstract", "private", "protected", "public", "internal", "expect", "actual",
        "lateinit", "vararg", "out", "get", "set", "field", "it",
    ),
    typeKeywords = setOf(
        "Int", "Long", "Short", "Byte", "UInt", "ULong", "Float", "Double", "Boolean", "Char",
        "String", "Unit", "Nothing", "Any", "List", "MutableList", "Map", "MutableMap",
        "Set", "MutableSet", "Array", "IntArray", "ByteArray", "Pair", "Triple", "Result",
        "Sequence", "Flow", "StateFlow", "MutableStateFlow", "Job", "CoroutineScope",
        "Modifier", "Color", "AnnotatedString",
    ),
    builtins = setOf("println", "print", "require", "check", "error", "TODO", "listOf", "mapOf", "setOf", "mutableListOf", "remember"),
    tripleDoubleQuote = true,
    backtickKind = BacktickKind.IDENTIFIER,
    atAnnotation = true,
)

private val JAVA_SPEC = LanguageSpec(
    keywords = setOf(
        "package", "import", "class", "interface", "enum", "record", "extends", "implements",
        "permits", "sealed", "non-sealed", "public", "private", "protected", "static", "final",
        "abstract", "synchronized", "volatile", "transient", "native", "strictfp", "default",
        "if", "else", "switch", "case", "for", "while", "do", "break", "continue", "return",
        "throw", "throws", "try", "catch", "finally", "new", "instanceof", "assert", "var", "yield",
    ),
    typeKeywords = setOf(
        "void", "boolean", "byte", "short", "int", "long", "float", "double", "char",
        "String", "Object", "Integer", "Long", "Double", "Boolean", "List", "Map", "Set",
        "Optional", "Stream", "CompletableFuture", "Exception", "Throwable",
    ),
    tripleDoubleQuote = true,
    atAnnotation = true,
)

private val JS_TS_SPEC = LanguageSpec(
    keywords = setOf(
        "import", "export", "from", "default", "as", "type", "interface", "class", "extends",
        "implements", "abstract", "enum", "namespace", "declare", "module", "function", "const",
        "let", "var", "if", "else", "switch", "case", "for", "of", "in", "while", "do",
        "break", "continue", "return", "throw", "try", "catch", "finally", "new", "delete",
        "typeof", "instanceof", "keyof", "readonly", "infer", "is", "satisfies", "async",
        "await", "yield", "void", "public", "private", "protected", "static", "override",
        "get", "set", "constructor", "debugger", "with",
    ),
    typeKeywords = setOf(
        "string", "number", "boolean", "any", "unknown", "never", "object", "symbol", "bigint",
        "Promise", "Record", "Partial", "Required", "Readonly", "Pick", "Omit", "Exclude",
        "Extract", "NonNullable", "ReturnType", "Array", "Map", "Set", "WeakMap", "WeakSet",
        "Date", "RegExp", "Error", "Buffer", "Uint8Array", "HTMLElement",
    ),
    builtins = setOf("console", "Math", "JSON", "Object", "Array", "Number", "String", "Boolean", "process", "window", "document", "fetch", "setTimeout", "clearTimeout"),
    backtickKind = BacktickKind.TEMPLATE_STRING,
    atAnnotation = true,
)

private val PYTHON_SPEC = LanguageSpec(
    keywords = setOf(
        "def", "class", "return", "yield", "if", "elif", "else", "for", "while", "break",
        "continue", "pass", "try", "except", "finally", "raise", "with", "as", "import", "from",
        "global", "nonlocal", "lambda", "and", "or", "not", "in", "is", "assert", "del",
        "async", "await", "match", "case",
    ),
    typeKeywords = setOf(
        "int", "float", "str", "bool", "bytes", "bytearray", "list", "dict", "set", "frozenset",
        "tuple", "object", "type", "Any", "Optional", "Union", "List", "Dict", "Set", "Tuple",
        "Callable", "Sequence", "Iterable", "Mapping", "Protocol", "Final", "Literal",
    ),
    builtins = setOf(
        "print", "len", "range", "enumerate", "zip", "map", "filter", "isinstance", "issubclass",
        "hasattr", "getattr", "setattr", "open", "sorted", "reversed", "sum", "min", "max",
        "abs", "round", "any", "all", "repr", "id", "input", "dir", "vars", "super",
    ),
    slashSlashComment = false,
    hashComment = true,
    blockComment = false,
    tripleDoubleQuote = true,
    tripleSingleQuote = true,
    pythonStringPrefix = true,
    atAnnotation = true,
)

private val GO_SPEC = LanguageSpec(
    keywords = setOf(
        "package", "import", "func", "type", "struct", "interface", "map", "chan", "var", "const",
        "if", "else", "switch", "case", "default", "select", "for", "range", "break", "continue",
        "goto", "fallthrough", "return", "go", "defer",
        // go.mod directives
        "module", "require", "replace", "exclude", "retract", "toolchain",
    ),
    typeKeywords = setOf(
        "bool", "byte", "rune", "string", "int", "int8", "int16", "int32", "int64",
        "uint", "uint8", "uint16", "uint32", "uint64", "uintptr", "float32", "float64",
        "complex64", "complex128", "error", "any", "comparable",
    ),
    literals = setOf("true", "false", "nil", "iota"),
    builtins = setOf(
        "append", "cap", "clear", "close", "complex", "copy", "delete", "imag",
        "len", "make", "max", "min", "new", "panic", "print", "println", "real", "recover",
    ),
    backtickKind = BacktickKind.RAW_STRING,
)

private val RUST_SPEC = LanguageSpec(
    keywords = setOf(
        "fn", "let", "mut", "const", "static", "struct", "enum", "trait", "impl", "type",
        "mod", "pub", "use", "crate", "super", "self", "Self", "as", "where", "if", "else",
        "match", "loop", "while", "for", "in", "break", "continue", "return", "async", "await",
        "move", "ref", "unsafe", "extern", "dyn", "union", "macro_rules",
    ),
    typeKeywords = setOf(
        "bool", "char", "str", "i8", "i16", "i32", "i64", "i128", "isize",
        "u8", "u16", "u32", "u64", "u128", "usize", "f32", "f64",
        "String", "Vec", "Option", "Result", "Box", "Rc", "Arc", "Cell", "RefCell",
        "HashMap", "HashSet", "BTreeMap", "BTreeSet", "Pin", "Future", "Cow",
    ),
    literals = setOf("true", "false", "Some", "None", "Ok", "Err", "self", "Self"),
    builtins = setOf("println", "eprintln", "format", "vec", "panic", "assert", "assert_eq", "dbg", "todo", "unimplemented"),
    singleQuoteString = false,
    rustSingleQuote = true,
    rustRawString = true,
    rustAttribute = true,
)

private val C_CPP_SPEC = LanguageSpec(
    keywords = setOf(
        "auto", "break", "case", "const", "constexpr", "consteval", "continue", "default", "do",
        "else", "enum", "extern", "for", "goto", "if", "inline", "register", "restrict", "return",
        "sizeof", "static", "struct", "switch", "typedef", "union", "volatile", "while",
        "class", "namespace", "template", "typename", "using", "public", "private", "protected",
        "virtual", "override", "final", "friend", "explicit", "mutable", "new", "delete",
        "try", "catch", "throw", "noexcept", "operator", "static_cast", "dynamic_cast",
        "reinterpret_cast", "const_cast", "decltype", "co_await", "co_yield", "co_return", "concept", "requires",
        // Objective-C keywords
        "id", "instancetype", "YES", "NO", "nil", "Nil", "nonatomic", "atomic", "strong", "weak", "assign", "copy", "readonly", "readwrite",
    ),
    typeKeywords = setOf(
        "void", "bool", "char", "short", "int", "long", "float", "double", "signed", "unsigned",
        "size_t", "ssize_t", "ptrdiff_t", "int8_t", "int16_t", "int32_t", "int64_t",
        "uint8_t", "uint16_t", "uint32_t", "uint64_t", "uintptr_t", "wchar_t", "char16_t", "char32_t",
        "string", "vector", "map", "unordered_map", "set", "unique_ptr", "shared_ptr", "optional", "string_view",
        "NSString", "NSArray", "NSDictionary", "NSObject", "NSInteger", "BOOL", "CGFloat",
    ),
    literals = setOf("true", "false", "nullptr", "NULL", "this", "self", "super", "YES", "NO", "nil"),
    hashDirective = true,
    atAnnotation = true,
)

private val SWIFT_SPEC = LanguageSpec(
    keywords = setOf(
        "import", "class", "struct", "enum", "protocol", "extension", "actor", "typealias",
        "associatedtype", "func", "init", "deinit", "subscript", "var", "let", "if", "else",
        "guard", "switch", "case", "default", "where", "for", "in", "while", "repeat", "break",
        "continue", "fallthrough", "return", "throw", "throws", "rethrows", "try", "catch",
        "defer", "do", "async", "await", "some", "any", "as", "is", "public", "private",
        "fileprivate", "internal", "open", "static", "final", "override", "mutating", "nonmutating",
        "lazy", "weak", "unowned", "inout", "convenience", "required", "optional", "indirect",
        "get", "set", "willSet", "didSet",
    ),
    typeKeywords = setOf(
        "Int", "Int8", "Int16", "Int32", "Int64", "UInt", "Float", "Double", "Bool", "String",
        "Character", "Void", "Any", "AnyObject", "Never", "Array", "Dictionary", "Set",
        "Optional", "Result", "Error", "Task", "Data", "URL", "Date", "View",
    ),
    tripleDoubleQuote = true,
    hashDirective = true,
    atAnnotation = true,
    backtickKind = BacktickKind.IDENTIFIER,
)

private val CSHARP_SPEC = LanguageSpec(
    keywords = setOf(
        "using", "namespace", "class", "struct", "interface", "enum", "record", "delegate",
        "event", "public", "private", "protected", "internal", "static", "readonly", "const",
        "volatile", "abstract", "virtual", "override", "sealed", "partial", "extern", "unsafe",
        "async", "await", "if", "else", "switch", "case", "default", "for", "foreach", "in",
        "while", "do", "break", "continue", "return", "yield", "throw", "try", "catch", "finally",
        "new", "is", "as", "typeof", "sizeof", "nameof", "ref", "out", "params", "lock",
        "var", "get", "set", "init", "where", "select", "from", "orderby", "group", "into",
    ),
    typeKeywords = setOf(
        "void", "bool", "byte", "sbyte", "short", "ushort", "int", "uint", "long", "ulong",
        "float", "double", "decimal", "char", "string", "object", "dynamic", "Task", "List",
        "Dictionary", "IEnumerable", "Action", "Func", "Guid", "DateTime",
    ),
    tripleDoubleQuote = true,
    hashDirective = true,
)

private val DART_SPEC = LanguageSpec(
    keywords = setOf(
        "import", "export", "part", "library", "show", "hide", "as", "deferred", "class",
        "mixin", "extension", "enum", "typedef", "extends", "implements", "with", "on",
        "abstract", "sealed", "base", "interface", "final", "const", "var", "late", "static",
        "external", "factory", "operator", "get", "set", "if", "else", "switch", "case",
        "default", "for", "in", "while", "do", "break", "continue", "return", "yield",
        "async", "await", "sync", "throw", "rethrow", "try", "catch", "finally", "assert",
        "is", "new", "required", "covariant",
    ),
    typeKeywords = setOf(
        "void", "bool", "int", "double", "num", "String", "Object", "dynamic", "Never",
        "List", "Map", "Set", "Future", "Stream", "Iterable", "Widget", "BuildContext", "State",
    ),
    tripleDoubleQuote = true,
    tripleSingleQuote = true,
    atAnnotation = true,
)

private val RUBY_SPEC = LanguageSpec(
    keywords = setOf(
        "def", "end", "class", "module", "include", "extend", "prepend", "require", "require_relative",
        "if", "elsif", "else", "unless", "case", "when", "for", "in", "while", "until", "do",
        "break", "next", "redo", "retry", "return", "yield", "begin", "rescue", "ensure", "raise",
        "and", "or", "not", "then", "alias", "defined?", "attr_reader", "attr_writer", "attr_accessor",
        "private", "protected", "public", "lambda", "proc",
    ),
    builtins = setOf("puts", "print", "p", "gem", "source", "pod", "target", "brew", "cask", "task", "desc"),
    slashSlashComment = false,
    hashComment = true,
    blockComment = false,
)

private val PHP_SPEC = LanguageSpec(
    keywords = setOf(
        "namespace", "use", "class", "interface", "trait", "enum", "extends", "implements",
        "function", "fn", "const", "public", "private", "protected", "static", "abstract",
        "final", "readonly", "if", "elseif", "else", "switch", "case", "default", "match",
        "for", "foreach", "as", "while", "do", "break", "continue", "return", "yield",
        "throw", "try", "catch", "finally", "new", "clone", "instanceof", "echo", "print",
        "require", "require_once", "include", "include_once", "global", "array", "isset", "empty", "unset",
    ),
    typeKeywords = setOf("int", "float", "string", "bool", "array", "object", "callable", "iterable", "void", "mixed", "never"),
    hashComment = true,
    dollarVar = true,
)

private val GROOVY_SCALA_SPEC = LanguageSpec(
    keywords = setOf(
        "package", "import", "class", "interface", "trait", "object", "enum", "def", "val", "var",
        "type", "given", "using", "extends", "with", "implements", "sealed", "case", "match",
        "if", "else", "for", "yield", "while", "do", "switch", "break", "continue", "return",
        "throw", "try", "catch", "finally", "new", "in", "as", "is", "private", "protected",
        "public", "override", "abstract", "final", "static", "implicit", "lazy", "synchronized",
        "plugins", "dependencies", "repositories", "android", "tasks", "implementation", "api", "testImplementation",
    ),
    typeKeywords = setOf("Int", "Long", "Double", "Float", "Boolean", "String", "Unit", "Any", "Option", "Seq", "List", "Map", "Set", "Future", "Either"),
    tripleDoubleQuote = true,
    tripleSingleQuote = true,
    atAnnotation = true,
)

private val LUA_SPEC = LanguageSpec(
    keywords = setOf(
        "and", "break", "do", "else", "elseif", "end", "for", "function", "goto", "if", "in",
        "local", "not", "or", "repeat", "return", "then", "until", "while",
    ),
    builtins = setOf("print", "pairs", "ipairs", "next", "type", "tonumber", "tostring", "require", "pcall", "xpcall", "error", "assert", "setmetatable", "getmetatable", "table", "string", "math", "os", "io"),
    slashSlashComment = false,
    blockComment = false,
    dashDashComment = true,
    luaBlockComment = true,
    pascalCaseTypes = false,
)

private val SQL_SPEC = LanguageSpec(
    keywords = setOf(
        "select", "distinct", "all", "from", "where", "join", "inner", "left", "right", "full",
        "outer", "cross", "natural", "on", "using", "group", "by", "having", "order", "asc",
        "desc", "nulls", "first", "last", "limit", "offset", "fetch", "next", "rows", "only",
        "insert", "into", "values", "update", "set", "delete", "merge", "replace", "upsert",
        "create", "alter", "drop", "truncate", "rename", "table", "index", "view", "materialized",
        "schema", "database", "trigger", "procedure", "function", "temp", "temporary", "if",
        "exists", "not", "and", "or", "in", "is", "like", "ilike", "similar", "between", "case",
        "when", "then", "else", "end", "as", "union", "intersect", "except", "with", "recursive",
        "over", "partition", "window", "primary", "foreign", "key", "references", "constraint",
        "unique", "check", "default", "auto_increment", "autoincrement", "identity", "generated",
        "begin", "start", "transaction", "commit", "rollback", "savepoint", "explain", "analyze",
        "grant", "revoke", "cascade", "restrict", "returning", "conflict", "do", "nothing",
    ),
    typeKeywords = setOf(
        "int", "integer", "bigint", "smallint", "tinyint", "serial", "bigserial", "float",
        "double", "real", "numeric", "decimal", "bool", "boolean", "char", "varchar", "nvarchar",
        "text", "clob", "blob", "bytea", "binary", "varbinary", "date", "time", "timestamp",
        "timestamptz", "datetime", "interval", "json", "jsonb", "uuid", "xml", "enum",
    ),
    literals = setOf("true", "false", "null", "unknown", "current_timestamp", "current_date", "current_time"),
    builtins = setOf("count", "sum", "avg", "min", "max", "coalesce", "nullif", "cast", "convert", "concat", "substring", "lower", "upper", "trim", "length", "now", "row_number", "rank", "dense_rank", "lag", "lead"),
    slashSlashComment = false,
    hashComment = true,
    dashDashComment = true,
    blockComment = true,
    backtickKind = BacktickKind.IDENTIFIER,
    caseInsensitive = true,
    pascalCaseTypes = false,
    sqlDoubleSingleQuoteEscape = true,
)

private val IDL_SPEC = LanguageSpec(
    keywords = setOf(
        // Protobuf, Thrift, GraphQL
        "syntax", "edition", "package", "import", "public", "weak", "option", "message", "enum",
        "service", "rpc", "returns", "stream", "oneof", "map", "repeated", "optional", "required",
        "reserved", "extensions", "extend", "to", "max",
        "namespace", "include", "struct", "union", "exception", "typedef", "const", "throws", "void", "oneway",
        "schema", "query", "mutation", "subscription", "fragment", "on", "type", "interface",
        "input", "scalar", "directive", "implements",
    ),
    typeKeywords = setOf(
        "double", "float", "int32", "int64", "uint32", "uint64", "sint32", "sint64",
        "fixed32", "fixed64", "sfixed32", "sfixed64", "bool", "string", "bytes",
        "i8", "i16", "i32", "i64", "binary", "list", "set",
        "Int", "Float", "String", "Boolean", "ID",
    ),
    hashComment = true,
    atAnnotation = true,
)

private val INFRA_SPEC = LanguageSpec(
    keywords = setOf(
        // HCL / Terraform / Nix / Nginx / CMake
        "resource", "data", "variable", "output", "locals", "module", "provider", "terraform",
        "backend", "required_providers", "lifecycle", "dynamic", "for_each", "count", "for", "in", "if", "else", "then",
        "let", "rec", "with", "inherit", "import", "assert",
        "server", "location", "upstream", "http", "events", "proxy_pass", "listen", "server_name", "root", "index", "return", "rewrite", "include",
        "cmake_minimum_required", "project", "add_executable", "add_library", "target_link_libraries",
        "target_include_directories", "find_package", "set", "option", "if", "elseif", "endif", "foreach", "endforeach", "function", "endfunction", "macro", "endmacro", "install",
    ),
    hashComment = true,
    pascalCaseTypes = false,
)

private val EXTRA_SCRIPT_SPEC = LanguageSpec(
    keywords = setOf(
        // Zig, Elixir, Erlang, Haskell, Perl, R, PowerShell
        "const", "var", "fn", "pub", "struct", "enum", "union", "error", "try", "catch", "defer",
        "errdefer", "comptime", "inline", "if", "else", "switch", "while", "for", "break", "continue", "return",
        "def", "defp", "defmodule", "defstruct", "do", "end", "when", "case", "cond", "with", "use", "alias", "import", "require", "receive",
        "module", "where", "data", "newtype", "type", "class", "instance", "deriving", "let", "in", "of",
        "sub", "my", "our", "local", "use", "package", "unless", "elsif", "foreach", "next", "last",
        "function", "param", "begin", "process", "elseif", "switch", "throw", "finally",
    ),
    hashComment = true,
    dashDashComment = true,
    dollarVar = true,
    atAnnotation = true,
)

private val FILENAME_LANGUAGE_MAP: Map<String, DetectedCodeLanguage> = mapOf(
    "dockerfile" to DetectedCodeLanguage("dockerfile", "Dockerfile"),
    "containerfile" to DetectedCodeLanguage("dockerfile", "Dockerfile"),
    "makefile" to DetectedCodeLanguage("makefile", "Makefile"),
    "gnumakefile" to DetectedCodeLanguage("makefile", "Makefile"),
    "justfile" to DetectedCodeLanguage("makefile", "Justfile"),
    "cmakelists.txt" to DetectedCodeLanguage("cmake", "CMake"),
    "gemfile" to DetectedCodeLanguage("ruby", "Ruby"),
    "rakefile" to DetectedCodeLanguage("ruby", "Ruby"),
    "podfile" to DetectedCodeLanguage("ruby", "Ruby"),
    "brewfile" to DetectedCodeLanguage("ruby", "Ruby"),
    "fastfile" to DetectedCodeLanguage("ruby", "Ruby"),
    "vagrantfile" to DetectedCodeLanguage("ruby", "Ruby"),
    "go.mod" to DetectedCodeLanguage("go", "Go Mod"),
    "go.sum" to DetectedCodeLanguage("go", "Go Sum"),
    "cargo.lock" to DetectedCodeLanguage("toml", "TOML"),
    ".zshrc" to DetectedCodeLanguage("shell", "Zsh"),
    ".bashrc" to DetectedCodeLanguage("shell", "Bash"),
    ".bash_profile" to DetectedCodeLanguage("shell", "Bash"),
    ".bash_aliases" to DetectedCodeLanguage("shell", "Bash"),
    ".profile" to DetectedCodeLanguage("shell", "Shell"),
    ".zprofile" to DetectedCodeLanguage("shell", "Zsh"),
    ".zshenv" to DetectedCodeLanguage("shell", "Zsh"),
    ".gitignore" to DetectedCodeLanguage("ini", "Gitignore"),
    ".dockerignore" to DetectedCodeLanguage("ini", "Ignore"),
    ".npmignore" to DetectedCodeLanguage("ini", "Ignore"),
    ".editorconfig" to DetectedCodeLanguage("ini", "EditorConfig"),
    ".gitconfig" to DetectedCodeLanguage("ini", "Git Config"),
    ".eslintrc" to DetectedCodeLanguage("jsonc", "JSON"),
    ".prettierrc" to DetectedCodeLanguage("jsonc", "JSON"),
    ".babelrc" to DetectedCodeLanguage("jsonc", "JSON"),
)

private val EXTENSION_LANGUAGE_MAP: Map<String, DetectedCodeLanguage> = mapOf(
    "kt" to DetectedCodeLanguage("kotlin", "Kotlin"),
    "kts" to DetectedCodeLanguage("kotlin", "Kotlin Script"),
    "kotlin" to DetectedCodeLanguage("kotlin", "Kotlin"),
    "java" to DetectedCodeLanguage("java", "Java"),
    "ts" to DetectedCodeLanguage("typescript", "TypeScript"),
    "mts" to DetectedCodeLanguage("typescript", "TypeScript"),
    "cts" to DetectedCodeLanguage("typescript", "TypeScript"),
    "typescript" to DetectedCodeLanguage("typescript", "TypeScript"),
    "tsx" to DetectedCodeLanguage("typescript", "TSX"),
    "js" to DetectedCodeLanguage("javascript", "JavaScript"),
    "mjs" to DetectedCodeLanguage("javascript", "JavaScript"),
    "cjs" to DetectedCodeLanguage("javascript", "JavaScript"),
    "javascript" to DetectedCodeLanguage("javascript", "JavaScript"),
    "jsx" to DetectedCodeLanguage("javascript", "JSX"),
    "py" to DetectedCodeLanguage("python", "Python"),
    "pyi" to DetectedCodeLanguage("python", "Python"),
    "pyw" to DetectedCodeLanguage("python", "Python"),
    "python" to DetectedCodeLanguage("python", "Python"),
    "go" to DetectedCodeLanguage("go", "Go"),
    "golang" to DetectedCodeLanguage("go", "Go"),
    "rs" to DetectedCodeLanguage("rust", "Rust"),
    "rust" to DetectedCodeLanguage("rust", "Rust"),
    "c" to DetectedCodeLanguage("c", "C"),
    "h" to DetectedCodeLanguage("c", "C/C++ Header"),
    "cc" to DetectedCodeLanguage("cpp", "C++"),
    "cpp" to DetectedCodeLanguage("cpp", "C++"),
    "cxx" to DetectedCodeLanguage("cpp", "C++"),
    "hpp" to DetectedCodeLanguage("cpp", "C++"),
    "hh" to DetectedCodeLanguage("cpp", "C++"),
    "hxx" to DetectedCodeLanguage("cpp", "C++"),
    "c++" to DetectedCodeLanguage("cpp", "C++"),
    "m" to DetectedCodeLanguage("objc", "Objective-C"),
    "mm" to DetectedCodeLanguage("objc", "Objective-C++"),
    "objc" to DetectedCodeLanguage("objc", "Objective-C"),
    "swift" to DetectedCodeLanguage("swift", "Swift"),
    "cs" to DetectedCodeLanguage("csharp", "C#"),
    "csharp" to DetectedCodeLanguage("csharp", "C#"),
    "dart" to DetectedCodeLanguage("dart", "Dart"),
    "rb" to DetectedCodeLanguage("ruby", "Ruby"),
    "ruby" to DetectedCodeLanguage("ruby", "Ruby"),
    "rake" to DetectedCodeLanguage("ruby", "Ruby"),
    "gemspec" to DetectedCodeLanguage("ruby", "Ruby"),
    "php" to DetectedCodeLanguage("php", "PHP"),
    "phtml" to DetectedCodeLanguage("php", "PHP"),
    "sh" to DetectedCodeLanguage("shell", "Shell"),
    "bash" to DetectedCodeLanguage("shell", "Bash"),
    "zsh" to DetectedCodeLanguage("shell", "Zsh"),
    "fish" to DetectedCodeLanguage("shell", "Fish"),
    "shell" to DetectedCodeLanguage("shell", "Shell"),
    "console" to DetectedCodeLanguage("shell", "Shell"),
    "terminal" to DetectedCodeLanguage("shell", "Shell"),
    "ps1" to DetectedCodeLanguage("powershell", "PowerShell"),
    "psm1" to DetectedCodeLanguage("powershell", "PowerShell"),
    "powershell" to DetectedCodeLanguage("powershell", "PowerShell"),
    "sql" to DetectedCodeLanguage("sql", "SQL"),
    "ddl" to DetectedCodeLanguage("sql", "SQL"),
    "dml" to DetectedCodeLanguage("sql", "SQL"),
    "mysql" to DetectedCodeLanguage("sql", "SQL"),
    "psql" to DetectedCodeLanguage("sql", "SQL"),
    "sqlite" to DetectedCodeLanguage("sql", "SQL"),
    "json" to DetectedCodeLanguage("json", "JSON"),
    "jsonl" to DetectedCodeLanguage("json", "JSONL"),
    "ndjson" to DetectedCodeLanguage("json", "NDJSON"),
    "webmanifest" to DetectedCodeLanguage("json", "JSON"),
    "jsonc" to DetectedCodeLanguage("jsonc", "JSONC"),
    "json5" to DetectedCodeLanguage("jsonc", "JSON5"),
    "yml" to DetectedCodeLanguage("yaml", "YAML"),
    "yaml" to DetectedCodeLanguage("yaml", "YAML"),
    "toml" to DetectedCodeLanguage("toml", "TOML"),
    "ini" to DetectedCodeLanguage("ini", "INI"),
    "cfg" to DetectedCodeLanguage("ini", "Config"),
    "conf" to DetectedCodeLanguage("ini", "Config"),
    "properties" to DetectedCodeLanguage("ini", "Properties"),
    "prop" to DetectedCodeLanguage("ini", "Properties"),
    "env" to DetectedCodeLanguage("ini", "Env"),
    "dotenv" to DetectedCodeLanguage("ini", "Env"),
    "html" to DetectedCodeLanguage("html", "HTML"),
    "htm" to DetectedCodeLanguage("html", "HTML"),
    "xhtml" to DetectedCodeLanguage("html", "HTML"),
    "vue" to DetectedCodeLanguage("html", "Vue"),
    "svelte" to DetectedCodeLanguage("html", "Svelte"),
    "xml" to DetectedCodeLanguage("xml", "XML"),
    "svg" to DetectedCodeLanguage("xml", "SVG"),
    "plist" to DetectedCodeLanguage("xml", "Plist"),
    "xaml" to DetectedCodeLanguage("xml", "XAML"),
    "csproj" to DetectedCodeLanguage("xml", "XML"),
    "pom" to DetectedCodeLanguage("xml", "XML"),
    "xsl" to DetectedCodeLanguage("xml", "XML"),
    "xsd" to DetectedCodeLanguage("xml", "XML"),
    "css" to DetectedCodeLanguage("css", "CSS"),
    "scss" to DetectedCodeLanguage("css", "SCSS"),
    "sass" to DetectedCodeLanguage("css", "SASS"),
    "less" to DetectedCodeLanguage("css", "LESS"),
    "md" to DetectedCodeLanguage("markdown", "Markdown"),
    "markdown" to DetectedCodeLanguage("markdown", "Markdown"),
    "mdx" to DetectedCodeLanguage("markdown", "MDX"),
    "dockerfile" to DetectedCodeLanguage("dockerfile", "Dockerfile"),
    "mk" to DetectedCodeLanguage("makefile", "Makefile"),
    "makefile" to DetectedCodeLanguage("makefile", "Makefile"),
    "cmake" to DetectedCodeLanguage("cmake", "CMake"),
    "groovy" to DetectedCodeLanguage("groovy", "Groovy"),
    "gradle" to DetectedCodeLanguage("groovy", "Gradle"),
    "scala" to DetectedCodeLanguage("scala", "Scala"),
    "sc" to DetectedCodeLanguage("scala", "Scala"),
    "lua" to DetectedCodeLanguage("lua", "Lua"),
    "proto" to DetectedCodeLanguage("proto", "Protobuf"),
    "protobuf" to DetectedCodeLanguage("proto", "Protobuf"),
    "thrift" to DetectedCodeLanguage("proto", "Thrift"),
    "graphql" to DetectedCodeLanguage("proto", "GraphQL"),
    "gql" to DetectedCodeLanguage("proto", "GraphQL"),
    "tf" to DetectedCodeLanguage("hcl", "Terraform"),
    "tfvars" to DetectedCodeLanguage("hcl", "Terraform"),
    "hcl" to DetectedCodeLanguage("hcl", "HCL"),
    "nix" to DetectedCodeLanguage("hcl", "Nix"),
    "nginx" to DetectedCodeLanguage("hcl", "Nginx"),
    "zig" to DetectedCodeLanguage("extra", "Zig"),
    "ex" to DetectedCodeLanguage("extra", "Elixir"),
    "exs" to DetectedCodeLanguage("extra", "Elixir"),
    "erl" to DetectedCodeLanguage("extra", "Erlang"),
    "hs" to DetectedCodeLanguage("extra", "Haskell"),
    "pl" to DetectedCodeLanguage("extra", "Perl"),
    "pm" to DetectedCodeLanguage("extra", "Perl"),
    "r" to DetectedCodeLanguage("extra", "R"),
    "diff" to DetectedCodeLanguage("diff", "Diff"),
    "patch" to DetectedCodeLanguage("diff", "Patch"),
    "log" to DetectedCodeLanguage("log", "Log"),
)

private val PLAIN_LANGUAGE = DetectedCodeLanguage("plain", "Plain Text")

internal fun detectCodeLanguage(
    filePathOrLang: String,
    content: String = "",
): DetectedCodeLanguage {
    val trimmed = filePathOrLang.trim()
    if (trimmed.isNotEmpty()) {
        val baseName = trimmed.trimEnd('/').substringAfterLast('/').substringAfterLast('\\').lowercase()
        FILENAME_LANGUAGE_MAP[baseName]?.let { return it }
        if (baseName.startsWith("dockerfile.")) {
            return DetectedCodeLanguage("dockerfile", "Dockerfile")
        }
        if (baseName.startsWith(".env.")) {
            return DetectedCodeLanguage("ini", "Env")
        }
        if (baseName == "tsconfig.json" || baseName == "jsconfig.json" || baseName.endsWith(".jsonc")) {
            return DetectedCodeLanguage("jsonc", "JSONC")
        }
        val dotIdx = baseName.lastIndexOf('.')
        val ext = if (dotIdx > 0 && dotIdx < baseName.lastIndex) {
            baseName.substring(dotIdx + 1)
        } else {
            baseName
        }
        EXTENSION_LANGUAGE_MAP[ext]?.let { return it }
    }

    // Fallback to shebang detection on first line for extensionless scripts
    val firstLine = content.lineSequence().firstOrNull()?.trim().orEmpty()
    if (firstLine.startsWith("#!")) {
        val lower = firstLine.lowercase()
        return when {
            lower.contains("python") -> DetectedCodeLanguage("python", "Python")
            lower.contains("zsh") -> DetectedCodeLanguage("shell", "Zsh")
            lower.contains("bash") -> DetectedCodeLanguage("shell", "Bash")
            lower.contains("fish") -> DetectedCodeLanguage("shell", "Fish")
            lower.contains("/sh") || lower.endsWith(" sh") -> DetectedCodeLanguage("shell", "Shell")
            lower.contains("node") || lower.contains("deno") || lower.contains("bun") ->
                DetectedCodeLanguage("javascript", "JavaScript")
            lower.contains("ruby") -> DetectedCodeLanguage("ruby", "Ruby")
            lower.contains("perl") -> DetectedCodeLanguage("extra", "Perl")
            lower.contains("lua") -> DetectedCodeLanguage("lua", "Lua")
            lower.contains("php") -> DetectedCodeLanguage("php", "PHP")
            else -> PLAIN_LANGUAGE
        }
    }

    return PLAIN_LANGUAGE
}

/**
 * Highlights a file's full text into per-line [AnnotatedString]s while preserving multi-line
 * comment, string, and markup tag state across lines.
 */
internal fun highlightFileLines(
    content: String,
    filePathOrLang: String,
): List<AnnotatedString> {
    val rawLines = content.split('\n')
    if (content.length > MAX_HIGHLIGHT_CHARS) {
        return rawLines.map { AnnotatedString(it) }
    }
    val detected = detectCodeLanguage(filePathOrLang, content)
    if (detected.id == "plain") {
        return rawLines.map { AnnotatedString(it) }
    }

    val state = LexerState()
    val out = ArrayList<AnnotatedString>(rawLines.size)
    for (rawLine in rawLines) {
        val cleanLine = rawLine.trimEnd('\r')
        if (cleanLine.isEmpty()) {
            out.add(AnnotatedString(""))
        } else if (cleanLine.length > MAX_HIGHLIGHT_LINE_LENGTH) {
            out.add(AnnotatedString(cleanLine))
        } else {
            out.add(highlightSingleLineWithState(cleanLine, detected.id, state))
        }
    }
    return out
}

/**
 * Highlights an entire file into a single [AnnotatedString] using Everforest Dark syntax colors.
 */
internal fun highlightFileContent(
    content: String,
    filePathOrLang: String,
): AnnotatedString {
    if (content.isEmpty()) return AnnotatedString("")
    val lines = highlightFileLines(content, filePathOrLang)
    return buildAnnotatedString {
        for (idx in lines.indices) {
            append(lines[idx])
            if (idx < lines.lastIndex) append('\n')
        }
    }
}

private fun specForLanguageId(langId: String): LanguageSpec? = when (langId) {
    "kotlin" -> KOTLIN_SPEC
    "java" -> JAVA_SPEC
    "typescript", "javascript" -> JS_TS_SPEC
    "python" -> PYTHON_SPEC
    "go" -> GO_SPEC
    "rust" -> RUST_SPEC
    "c", "cpp", "objc" -> C_CPP_SPEC
    "swift" -> SWIFT_SPEC
    "csharp" -> CSHARP_SPEC
    "dart" -> DART_SPEC
    "ruby" -> RUBY_SPEC
    "php" -> PHP_SPEC
    "groovy", "scala" -> GROOVY_SCALA_SPEC
    "lua" -> LUA_SPEC
    "sql" -> SQL_SPEC
    "proto" -> IDL_SPEC
    "hcl", "cmake" -> INFRA_SPEC
    "powershell", "extra" -> EXTRA_SCRIPT_SPEC
    else -> null
}

private fun highlightSingleLineWithState(
    line: String,
    langId: String,
    state: LexerState,
): AnnotatedString = when (langId) {
    "json" -> highlightJsonLine(line, allowComments = false, state = state)
    "jsonc" -> highlightJsonLine(line, allowComments = true, state = state)
    "yaml" -> highlightYamlLine(line)
    "toml" -> highlightTomlLine(line, state)
    "ini" -> highlightIniLine(line)
    "html", "xml" -> highlightMarkupLine(line, allowEmbedded = langId == "html", state = state)
    "css" -> highlightCssLine(line, state)
    "shell" -> highlightShellLine(line)
    "dockerfile" -> highlightDockerfileLine(line)
    "makefile" -> highlightMakefileLine(line)
    "markdown" -> highlightMarkdownSourceLine(line, state)
    "diff" -> highlightDiffLine(line)
    "log" -> highlightLogLine(line)
    else -> {
        val spec = specForLanguageId(langId)
        if (spec != null) {
            highlightCodeSpecLine(line, spec, state)
        } else {
            AnnotatedString(line)
        }
    }
}

// ---------------------------------------------------------------------------
// 1. General C-like / Scripting / SQL / IDL Stateful Line Lexer
// ---------------------------------------------------------------------------

private fun highlightCodeSpecLine(
    line: String,
    spec: LanguageSpec,
    state: LexerState,
): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = line.length

    // Handle active multi-line block continuation at line start
    if (state.blockMode != BlockMode.NONE) {
        i = consumeContinuedBlock(line, 0, state)
    }

    // Leading preprocessor directive (`#include`, `#define`, `#if`, `#pragma`)
    if (i == 0 && spec.hashDirective) {
        var ws = 0
        while (ws < n && (line[ws] == ' ' || line[ws] == '\t')) ws++
        if (ws < n && line[ws] == '#') {
            if (ws > 0) append(line.substring(0, ws))
            var dEnd = ws + 1
            while (dEnd < n && (line[dEnd] == ' ' || line[dEnd] == '\t')) dEnd++
            while (dEnd < n && (line[dEnd].isLetterOrDigit() || line[dEnd] == '_')) dEnd++
            val directive = line.substring(ws, dEnd)
            withStyle(SyntaxStyles.Directive) { append(directive) }
            i = dEnd
            if (directive.endsWith("include") || directive.endsWith("import")) {
                var sp = i
                while (sp < n && (line[sp] == ' ' || line[sp] == '\t')) sp++
                if (sp < n && line[sp] == '<') {
                    val close = line.indexOf('>', sp + 1)
                    if (close > sp) {
                        append(line.substring(i, sp))
                        withStyle(SyntaxStyles.StringLit) { append(line.substring(sp, close + 1)) }
                        i = close + 1
                    }
                }
            }
        }
    }

    while (i < n) {
        val c = line[i]

        // Lua block comment `--[[ ... ]]`
        if (spec.luaBlockComment && c == '-' && line.startsWith("--[[", i)) {
            val close = line.indexOf("]]", i + 4)
            if (close >= 0) {
                withStyle(SyntaxStyles.Comment) { append(line.substring(i, close + 2)) }
                i = close + 2
            } else {
                withStyle(SyntaxStyles.Comment) { append(line.substring(i)) }
                state.blockMode = BlockMode.LUA_BLOCK_COMMENT
                return@buildAnnotatedString
            }
            continue
        }

        // Standard block comment `/* ... */`
        if (spec.blockComment && c == '/' && i + 1 < n && line[i + 1] == '*') {
            val close = line.indexOf("*/", i + 2)
            if (close >= 0) {
                withStyle(SyntaxStyles.Comment) { append(line.substring(i, close + 2)) }
                i = close + 2
            } else {
                withStyle(SyntaxStyles.Comment) { append(line.substring(i)) }
                state.blockMode = BlockMode.BLOCK_COMMENT
                return@buildAnnotatedString
            }
            continue
        }

        // Line comments: `//`, `#`, `--`
        if (
            (spec.slashSlashComment && c == '/' && i + 1 < n && line[i + 1] == '/') ||
            (spec.hashComment && c == '#') ||
            (spec.dashDashComment && c == '-' && i + 1 < n && line[i + 1] == '-')
        ) {
            withStyle(SyntaxStyles.Comment) { append(line.substring(i)) }
            return@buildAnnotatedString
        }

        // Rust attributes `#[...]` / `#![...]`
        if (spec.rustAttribute && c == '#' && (line.startsWith("#[", i) || line.startsWith("#![", i))) {
            val prefixLen = if (line.startsWith("#![", i)) 3 else 2
            var end = i + prefixLen
            while (end < n && (line[end].isLetterOrDigit() || line[end] == '_' || line[end] == ':')) end++
            withStyle(SyntaxStyles.Directive) { append(line.substring(i, end)) }
            i = end
            continue
        }

        // Annotations / decorators (`@Composable`, `@Override`, `@dataclass`)
        if (spec.atAnnotation && c == '@' && i + 1 < n && (line[i + 1].isLetter() || line[i + 1] == '_')) {
            var end = i + 1
            while (end < n && (line[end].isLetterOrDigit() || line[end] == '_' || line[end] == '.')) end++
            withStyle(SyntaxStyles.Directive) { append(line.substring(i, end)) }
            i = end
            continue
        }

        // Rust raw strings `r"..."`, `r#"..."#`, `br#"..."#`
        if (spec.rustRawString && (c == 'r' || (c == 'b' && i + 1 < n && line[i + 1] == 'r'))) {
            val rawInfo = matchRustRawStringStart(line, i)
            if (rawInfo != null) {
                val (quoteIdx, hashes) = rawInfo
                val closing = "\"" + "#".repeat(hashes)
                val close = line.indexOf(closing, quoteIdx + 1)
                if (close >= 0) {
                    val end = close + closing.length
                    withStyle(SyntaxStyles.StringLit) { append(line.substring(i, end)) }
                    i = end
                } else {
                    withStyle(SyntaxStyles.StringLit) { append(line.substring(i)) }
                    state.blockMode = BlockMode.RAW_HASH_STRING
                    state.rawHashes = hashes
                    return@buildAnnotatedString
                }
                continue
            }
        }

        // Python string prefixes `f"..."`, `r"..."`, `b"..."`, `fr"""..."""`
        if (spec.pythonStringPrefix && isPythonStringPrefixAt(line, i)) {
            val prefixLen = pythonStringPrefixLen(line, i)
            if (prefixLen > 0) {
                withStyle(SyntaxStyles.Directive) { append(line.substring(i, i + prefixLen)) }
                i += prefixLen
                continue
            }
        }

        // Triple-quoted strings `"""` / `'''`
        if (
            (spec.tripleDoubleQuote && c == '"' && line.startsWith("\"\"\"", i)) ||
            (spec.tripleSingleQuote && c == '\'' && line.startsWith("'''", i))
        ) {
            val delim = if (c == '"') "\"\"\"" else "'''"
            val close = line.indexOf(delim, i + 3)
            if (close >= 0) {
                val end = close + 3
                withStyle(SyntaxStyles.StringLit) { append(line.substring(i, end)) }
                i = end
            } else {
                withStyle(SyntaxStyles.StringLit) { append(line.substring(i)) }
                state.blockMode = if (c == '"') BlockMode.TRIPLE_DOUBLE_STRING else BlockMode.TRIPLE_SINGLE_STRING
                return@buildAnnotatedString
            }
            continue
        }

        // Double-quoted string
        if (c == '"') {
            i = appendQuotedString(line, i, '"', sqlEscape = false)
            continue
        }

        // Single-quoted string or Rust char/lifetime
        if (c == '\'') {
            if (spec.rustSingleQuote) {
                val charEnd = matchRustCharLiteralEnd(line, i)
                if (charEnd > i) {
                    withStyle(SyntaxStyles.StringLit) { append(line.substring(i, charEnd)) }
                    i = charEnd
                    continue
                }
                if (i + 1 < n && (line[i + 1].isLetter() || line[i + 1] == '_')) {
                    var end = i + 2
                    while (end < n && (line[end].isLetterOrDigit() || line[end] == '_')) end++
                    withStyle(SyntaxStyles.Directive) { append(line.substring(i, end)) }
                    i = end
                    continue
                }
            } else if (spec.singleQuoteString) {
                i = appendQuotedString(line, i, '\'', sqlEscape = spec.sqlDoubleSingleQuoteEscape)
                continue
            }
        }

        // Backtick string / template string / identifier
        if (c == '`' && spec.backtickKind != BacktickKind.NONE) {
            when (spec.backtickKind) {
                BacktickKind.IDENTIFIER -> {
                    val close = line.indexOf('`', i + 1)
                    val end = if (close >= 0) close + 1 else n
                    withStyle(SyntaxStyles.Property) { append(line.substring(i, end)) }
                    i = end
                }
                BacktickKind.RAW_STRING, BacktickKind.TEMPLATE_STRING -> {
                    val allowEscape = spec.backtickKind == BacktickKind.TEMPLATE_STRING
                    var end = i + 1
                    var closed = false
                    while (end < n) {
                        if (allowEscape && line[end] == '\\' && end + 1 < n) {
                            end += 2
                            continue
                        }
                        if (line[end] == '`') {
                            end++
                            closed = true
                            break
                        }
                        end++
                    }
                    withStyle(SyntaxStyles.StringLit) { append(line.substring(i, end)) }
                    if (!closed) {
                        state.blockMode = BlockMode.BACKTICK_STRING
                        return@buildAnnotatedString
                    }
                    i = end
                }
                BacktickKind.NONE -> Unit
            }
            continue
        }

        // Dollar variables (`$var` in PHP / PowerShell)
        if (spec.dollarVar && c == '$' && i + 1 < n && (line[i + 1].isLetter() || line[i + 1] == '_')) {
            var end = i + 2
            while (end < n && (line[end].isLetterOrDigit() || line[end] == '_')) end++
            withStyle(SyntaxStyles.Property) { append(line.substring(i, end)) }
            i = end
            continue
        }

        // Numeric literals (hex, binary, octal, float, suffixes)
        if (c.isDigit() && (i == 0 || !isIdentPart(line[i - 1]))) {
            val end = scanNumberEnd(line, i)
            withStyle(SyntaxStyles.Number) { append(line.substring(i, end)) }
            i = end
            continue
        }

        // Identifiers, keywords, types, functions, literals
        if (c.isLetter() || c == '_') {
            var end = i + 1
            while (end < n && isIdentPart(line[end])) end++
            val word = line.substring(i, end)
            val lookup = if (spec.caseInsensitive) word.lowercase() else word

            // Check whether immediately followed by `(` (or Rust macro `!`)
            var nextNonWs = end
            while (nextNonWs < n && line[nextNonWs] == ' ') nextNonWs++
            val isCall = nextNonWs < n && line[nextNonWs] == '('
            val isRustMacro = spec.rustAttribute && end < n && line[end] == '!'

            val style = when {
                lookup in spec.keywords -> SyntaxStyles.Keyword
                lookup in spec.literals -> SyntaxStyles.Literal
                lookup in spec.typeKeywords -> SyntaxStyles.Type
                isRustMacro -> SyntaxStyles.Directive
                lookup in spec.builtins -> SyntaxStyles.Function
                isCall -> SyntaxStyles.Function
                spec.pascalCaseTypes && isPascalCaseType(word) -> SyntaxStyles.Type
                isScreamingConstant(word) -> SyntaxStyles.Number
                else -> SyntaxStyles.Plain
            }
            if (isRustMacro) {
                withStyle(style) { append(line.substring(i, end + 1)) }
                i = end + 1
            } else {
                withStyle(style) { append(word) }
                i = end
            }
            continue
        }

        // Operators and punctuation
        if (c in OPERATOR_CHARS) {
            withStyle(SyntaxStyles.Operator) { append(c) }
            i++
            continue
        }

        append(c)
        i++
    }
}

private val OPERATOR_CHARS = charArrayOf('=', '+', '-', '*', '/', '%', '!', '&', '|', '^', '~', '<', '>', '?', ':')

private fun AnnotatedString.Builder.consumeContinuedBlock(
    line: String,
    start: Int,
    state: LexerState,
): Int {
    val closeToken = when (state.blockMode) {
        BlockMode.BLOCK_COMMENT -> "*/"
        BlockMode.LUA_BLOCK_COMMENT -> "]]"
        BlockMode.HTML_COMMENT -> "-->"
        BlockMode.TRIPLE_DOUBLE_STRING -> "\"\"\""
        BlockMode.TRIPLE_SINGLE_STRING -> "'''"
        BlockMode.BACKTICK_STRING -> "`"
        BlockMode.RAW_HASH_STRING -> "\"" + "#".repeat(state.rawHashes)
        BlockMode.NONE -> return start
    }
    val style = when (state.blockMode) {
        BlockMode.BLOCK_COMMENT, BlockMode.LUA_BLOCK_COMMENT, BlockMode.HTML_COMMENT -> SyntaxStyles.Comment
        else -> SyntaxStyles.StringLit
    }

    val closeIdx = if (state.blockMode == BlockMode.BACKTICK_STRING) {
        findUnescapedChar(line, start, '`')
    } else {
        line.indexOf(closeToken, start)
    }

    return if (closeIdx >= 0) {
        val end = closeIdx + closeToken.length
        withStyle(style) { append(line.substring(start, end)) }
        state.blockMode = BlockMode.NONE
        state.rawHashes = 0
        end
    } else {
        withStyle(style) { append(line.substring(start)) }
        line.length
    }
}

private fun findUnescapedChar(line: String, start: Int, target: Char): Int {
    var i = start
    while (i < line.length) {
        if (line[i] == '\\' && i + 1 < line.length) {
            i += 2
            continue
        }
        if (line[i] == target) return i
        i++
    }
    return -1
}

private fun AnnotatedString.Builder.appendQuotedString(
    line: String,
    start: Int,
    quote: Char,
    sqlEscape: Boolean,
): Int {
    val n = line.length
    var i = start + 1
    var segStart = start
    while (i < n) {
        val ch = line[i]
        if (sqlEscape && ch == '\'' && i + 1 < n && line[i + 1] == '\'') {
            if (i > segStart) {
                withStyle(SyntaxStyles.StringLit) { append(line.substring(segStart, i)) }
            }
            withStyle(SyntaxStyles.Escape) { append("''") }
            i += 2
            segStart = i
            continue
        }
        if (!sqlEscape && ch == '\\' && i + 1 < n) {
            if (i > segStart) {
                withStyle(SyntaxStyles.StringLit) { append(line.substring(segStart, i)) }
            }
            val escEnd = (i + 2).coerceAtMost(n)
            withStyle(SyntaxStyles.Escape) { append(line.substring(i, escEnd)) }
            i = escEnd
            segStart = i
            continue
        }
        if (ch == quote) {
            withStyle(SyntaxStyles.StringLit) { append(line.substring(segStart, i + 1)) }
            return i + 1
        }
        i++
    }
    withStyle(SyntaxStyles.StringLit) { append(line.substring(segStart, n)) }
    return n
}

private fun matchRustRawStringStart(line: String, start: Int): Pair<Int, Int>? {
    val n = line.length
    var idx = start
    if (idx < n && line[idx] == 'b') idx++
    if (idx >= n || line[idx] != 'r') return null
    idx++
    var hashes = 0
    while (idx < n && line[idx] == '#') {
        hashes++
        idx++
    }
    if (idx < n && line[idx] == '"') {
        return idx to hashes
    }
    return null
}

private fun matchRustCharLiteralEnd(line: String, start: Int): Int {
    val n = line.length
    if (start + 2 >= n || line[start] != '\'') return start
    if (line[start + 1] == '\\') {
        var idx = start + 2
        while (idx < n && idx < start + 14) {
            if (line[idx] == '\'') return idx + 1
            if (line[idx] == '\n') break
            idx++
        }
        return start
    }
    return if (line[start + 2] == '\'') start + 3 else start
}

private fun isPythonStringPrefixAt(line: String, start: Int): Boolean {
    if (start > 0 && isIdentPart(line[start - 1])) return false
    return pythonStringPrefixLen(line, start) > 0
}

private fun pythonStringPrefixLen(line: String, start: Int): Int {
    val n = line.length
    val c1 = line[start].lowercaseChar()
    if (c1 !in charArrayOf('r', 'f', 'b', 'u')) return 0
    if (start + 1 < n && (line[start + 1] == '"' || line[start + 1] == '\'')) return 1
    if (start + 2 < n) {
        val c2 = line[start + 1].lowercaseChar()
        if (c2 in charArrayOf('r', 'f', 'b', 'u') && c2 != c1 && (line[start + 2] == '"' || line[start + 2] == '\'')) {
            return 2
        }
    }
    return 0
}

private fun scanNumberEnd(line: String, start: Int): Int {
    val n = line.length
    var i = start
    if (line[i] == '0' && i + 1 < n && line[i + 1].lowercaseChar() in charArrayOf('x', 'b', 'o')) {
        i += 2
        while (i < n && (line[i].isDigit() || line[i].lowercaseChar() in 'a'..'f' || line[i] == '_')) i++
    } else {
        while (i < n && (line[i].isDigit() || line[i] == '_')) i++
        if (i + 1 < n && line[i] == '.' && line[i + 1].isDigit()) {
            i++
            while (i < n && (line[i].isDigit() || line[i] == '_')) i++
        }
        if (i < n && (line[i] == 'e' || line[i] == 'E')) {
            var eIdx = i + 1
            if (eIdx < n && (line[eIdx] == '+' || line[eIdx] == '-')) eIdx++
            if (eIdx < n && line[eIdx].isDigit()) {
                i = eIdx
                while (i < n && (line[i].isDigit() || line[i] == '_')) i++
            }
        }
    }
    // Optional numeric type suffix (L, f, d, u64, i32, etc.)
    while (i < n && (line[i].isLetterOrDigit() || line[i] == '_')) i++
    return i
}

private fun isIdentPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

private fun isPascalCaseType(word: String): Boolean {
    if (word.length < 2 || !word[0].isUpperCase()) return false
    var hasLower = false
    for (idx in 1 until word.length) {
        if (word[idx].isLowerCase()) {
            hasLower = true
            break
        }
    }
    return hasLower
}

private fun isScreamingConstant(word: String): Boolean {
    if (word.length < 2 || !word.contains('_')) return false
    var hasLetter = false
    for (ch in word) {
        if (ch.isUpperCase()) {
            hasLetter = true
        } else if (!ch.isDigit() && ch != '_') {
            return false
        }
    }
    return hasLetter
}

// ---------------------------------------------------------------------------
// 2. JSON / JSONC Lexer
// ---------------------------------------------------------------------------

private fun highlightJsonLine(
    line: String,
    allowComments: Boolean,
    state: LexerState,
): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = line.length
    if (allowComments && state.blockMode == BlockMode.BLOCK_COMMENT) {
        i = consumeContinuedBlock(line, 0, state)
    }

    while (i < n) {
        val c = line[i]
        if (allowComments && c == '/' && i + 1 < n && line[i + 1] == '/') {
            withStyle(SyntaxStyles.Comment) { append(line.substring(i)) }
            return@buildAnnotatedString
        }
        if (allowComments && c == '/' && i + 1 < n && line[i + 1] == '*') {
            val close = line.indexOf("*/", i + 2)
            if (close >= 0) {
                withStyle(SyntaxStyles.Comment) { append(line.substring(i, close + 2)) }
                i = close + 2
            } else {
                withStyle(SyntaxStyles.Comment) { append(line.substring(i)) }
                state.blockMode = BlockMode.BLOCK_COMMENT
                return@buildAnnotatedString
            }
            continue
        }
        if (c == '"') {
            var end = i + 1
            while (end < n && line[end] != '"') {
                if (line[end] == '\\' && end + 1 < n) end += 2 else end++
            }
            if (end < n) end++
            var after = end
            while (after < n && (line[after] == ' ' || line[after] == '\t')) after++
            val isKey = after < n && line[after] == ':'
            withStyle(if (isKey) SyntaxStyles.KeyBold else SyntaxStyles.StringLit) {
                append(line.substring(i, end))
            }
            i = end
            continue
        }
        if (c == '-' && i + 1 < n && line[i + 1].isDigit()) {
            val end = scanNumberEnd(line, i + 1)
            withStyle(SyntaxStyles.Number) { append(line.substring(i, end)) }
            i = end
            continue
        }
        if (c.isDigit()) {
            val end = scanNumberEnd(line, i)
            withStyle(SyntaxStyles.Number) { append(line.substring(i, end)) }
            i = end
            continue
        }
        if (c.isLetter()) {
            var end = i + 1
            while (end < n && (line[end].isLetterOrDigit() || line[end] == '_')) end++
            val word = line.substring(i, end)
            val style = when (word) {
                "true", "false", "null" -> SyntaxStyles.Literal
                else -> SyntaxStyles.Plain
            }
            withStyle(style) { append(word) }
            i = end
            continue
        }
        if (c in charArrayOf('{', '}', '[', ']', ':', ',')) {
            withStyle(SyntaxStyles.Punctuation) { append(c) }
            i++
            continue
        }
        append(c)
        i++
    }
}

// ---------------------------------------------------------------------------
// 3. YAML Lexer
// ---------------------------------------------------------------------------

private fun highlightYamlLine(line: String): AnnotatedString = buildAnnotatedString {
    val trimmed = line.trim()
    if (trimmed == "---" || trimmed == "...") {
        withStyle(SyntaxStyles.Directive) { append(line) }
        return@buildAnnotatedString
    }
    var i = 0
    val n = line.length
    while (i < n && (line[i] == ' ' || line[i] == '\t')) {
        append(line[i])
        i++
    }
    if (i < n && line[i] == '#') {
        withStyle(SyntaxStyles.Comment) { append(line.substring(i)) }
        return@buildAnnotatedString
    }
    // Optional list bullet `- `
    if (i < n && line[i] == '-' && (i + 1 == n || line[i + 1] == ' ')) {
        withStyle(SyntaxStyles.Operator) { append('-') }
        i++
        while (i < n && line[i] == ' ') {
            append(' ')
            i++
        }
    }

    // Check for `key:` mapping at current position
    val colonIdx = findYamlKeyColon(line, i)
    if (colonIdx > i) {
        withStyle(SyntaxStyles.KeyBold) { append(line.substring(i, colonIdx)) }
        withStyle(SyntaxStyles.Punctuation) { append(':') }
        i = colonIdx + 1
    }

    // Highlight value side
    while (i < n) {
        val c = line[i]
        if (c == '#' && (i == 0 || line[i - 1].isWhitespace())) {
            withStyle(SyntaxStyles.Comment) { append(line.substring(i)) }
            return@buildAnnotatedString
        }
        if (c == '"' || c == '\'') {
            i = appendQuotedString(line, i, c, sqlEscape = false)
            continue
        }
        if ((c == '&' || c == '*' || c == '!') && i + 1 < n && !line[i + 1].isWhitespace()) {
            var end = i + 1
            while (end < n && !line[end].isWhitespace() && line[end] != ',' && line[end] != ']' && line[end] != '}') end++
            withStyle(if (c == '!') SyntaxStyles.Directive else SyntaxStyles.Type) { append(line.substring(i, end)) }
            i = end
            continue
        }
        if ((c == '|' || c == '>') && colonIdx >= 0 && i == colonIdx + 2) {
            var end = i + 1
            while (end < n && (line[end] == '-' || line[end] == '+' || line[end].isDigit())) end++
            withStyle(SyntaxStyles.Operator) { append(line.substring(i, end)) }
            i = end
            continue
        }
        if (!c.isWhitespace() && c !in charArrayOf('[', ']', '{', '}', ',')) {
            var end = i
            while (end < n && !line[end].isWhitespace() && line[end] !in charArrayOf('[', ']', '{', '}', ',', '#')) end++
            val token = line.substring(i, end)
            val lower = token.lowercase()
            val style = when {
                lower in setOf("true", "false", "null", "~", "yes", "no") -> SyntaxStyles.Literal
                isSimpleNumber(token) -> SyntaxStyles.Number
                else -> SyntaxStyles.StringLit
            }
            withStyle(style) { append(token) }
            i = end
            continue
        }
        append(c)
        i++
    }
}

private fun findYamlKeyColon(line: String, start: Int): Int {
    val n = line.length
    if (start >= n) return -1
    if (line[start] == '"' || line[start] == '\'') {
        val q = line[start]
        var idx = start + 1
        while (idx < n && line[idx] != q) {
            if (line[idx] == '\\' && idx + 1 < n) idx += 2 else idx++
        }
        if (idx + 1 < n && line[idx + 1] == ':') return idx + 1
        return -1
    }
    var idx = start
    while (idx < n) {
        val ch = line[idx]
        if (ch == '#' && idx > start && line[idx - 1].isWhitespace()) return -1
        if (ch == ':' && (idx + 1 == n || line[idx + 1] == ' ' || line[idx + 1] == '\t')) {
            return idx
        }
        idx++
    }
    return -1
}

private fun isSimpleNumber(token: String): Boolean {
    if (token.isEmpty()) return false
    var i = if (token[0] == '-' || token[0] == '+') 1 else 0
    if (i >= token.length) return false
    if (token.startsWith("0x", i) || token.startsWith("0o", i) || token.startsWith("0b", i)) {
        return token.length > i + 2
    }
    var sawDigit = false
    var sawDot = false
    while (i < token.length) {
        val c = token[i]
        when {
            c.isDigit() || c == '_' -> sawDigit = true
            c == '.' && !sawDot -> sawDot = true
            else -> return false
        }
        i++
    }
    return sawDigit
}

// ---------------------------------------------------------------------------
// 4. TOML & INI / Properties Lexers
// ---------------------------------------------------------------------------

private fun highlightTomlLine(line: String, state: LexerState): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = line.length
    if (state.blockMode == BlockMode.TRIPLE_DOUBLE_STRING || state.blockMode == BlockMode.TRIPLE_SINGLE_STRING) {
        i = consumeContinuedBlock(line, 0, state)
    }
    while (i < n && (line[i] == ' ' || line[i] == '\t')) {
        append(line[i])
        i++
    }
    if (i >= n) return@buildAnnotatedString
    if (line[i] == '#') {
        withStyle(SyntaxStyles.Comment) { append(line.substring(i)) }
        return@buildAnnotatedString
    }
    // Section table `[section]` / `[[array.table]]`
    if (line[i] == '[') {
        val close = line.lastIndexOf(']')
        if (close > i) {
            withStyle(SyntaxStyles.Heading) { append(line.substring(i, close + 1)) }
            i = close + 1
        }
    } else {
        val eqIdx = line.indexOf('=', i)
        if (eqIdx > i) {
            val keyPart = line.substring(i, eqIdx)
            if (!keyPart.contains('#')) {
                withStyle(SyntaxStyles.KeyBold) { append(keyPart) }
                withStyle(SyntaxStyles.Operator) { append('=') }
                i = eqIdx + 1
            }
        }
    }

    while (i < n) {
        val c = line[i]
        if (c == '#') {
            withStyle(SyntaxStyles.Comment) { append(line.substring(i)) }
            return@buildAnnotatedString
        }
        if (line.startsWith("\"\"\"", i) || line.startsWith("'''", i)) {
            val delim = line.substring(i, i + 3)
            val close = line.indexOf(delim, i + 3)
            if (close >= 0) {
                withStyle(SyntaxStyles.StringLit) { append(line.substring(i, close + 3)) }
                i = close + 3
            } else {
                withStyle(SyntaxStyles.StringLit) { append(line.substring(i)) }
                state.blockMode = if (c == '"') BlockMode.TRIPLE_DOUBLE_STRING else BlockMode.TRIPLE_SINGLE_STRING
                return@buildAnnotatedString
            }
            continue
        }
        if (c == '"' || c == '\'') {
            i = appendQuotedString(line, i, c, sqlEscape = false)
            continue
        }
        if (c.isDigit() || (c == '-' && i + 1 < n && line[i + 1].isDigit())) {
            var end = i + 1
            while (end < n && (line[end].isLetterOrDigit() || line[end] in charArrayOf('.', '_', '-', ':', '+'))) end++
            withStyle(SyntaxStyles.Number) { append(line.substring(i, end)) }
            i = end
            continue
        }
        if (c.isLetter()) {
            var end = i + 1
            while (end < n && isIdentPart(line[end])) end++
            val word = line.substring(i, end)
            val style = if (word == "true" || word == "false") SyntaxStyles.Literal else SyntaxStyles.Plain
            withStyle(style) { append(word) }
            i = end
            continue
        }
        append(c)
        i++
    }
}

private fun highlightIniLine(line: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = line.length
    while (i < n && (line[i] == ' ' || line[i] == '\t')) {
        append(line[i])
        i++
    }
    if (i >= n) return@buildAnnotatedString
    if (line[i] == '#' || line[i] == ';') {
        withStyle(SyntaxStyles.Comment) { append(line.substring(i)) }
        return@buildAnnotatedString
    }
    if (line[i] == '[') {
        val close = line.indexOf(']', i + 1)
        if (close > i) {
            withStyle(SyntaxStyles.Heading) { append(line.substring(i, close + 1)) }
            if (close + 1 < n) append(line.substring(close + 1))
            return@buildAnnotatedString
        }
    }
    val eqIdx = line.indexOf('=', i)
    if (eqIdx > i) {
        withStyle(SyntaxStyles.KeyBold) { append(line.substring(i, eqIdx)) }
        withStyle(SyntaxStyles.Operator) { append('=') }
        val valuePart = line.substring(eqIdx + 1)
        val trimmedVal = valuePart.trim()
        val valStyle = when {
            trimmedVal.equals("true", ignoreCase = true) || trimmedVal.equals("false", ignoreCase = true) -> SyntaxStyles.Literal
            isSimpleNumber(trimmedVal) -> SyntaxStyles.Number
            else -> SyntaxStyles.StringLit
        }
        withStyle(valStyle) { append(valuePart) }
        return@buildAnnotatedString
    }
    append(line.substring(i))
}

// ---------------------------------------------------------------------------
// 5. HTML / XML / SVG / Vue / Svelte Markup Lexer (with embedded Script & Style)
// ---------------------------------------------------------------------------

private fun highlightMarkupLine(
    line: String,
    allowEmbedded: Boolean,
    state: LexerState,
): AnnotatedString {
    if (allowEmbedded && state.markupEmbedded != MarkupEmbeddedMode.NONE) {
        val lower = line.lowercase()
        val closeTag = if (state.markupEmbedded == MarkupEmbeddedMode.SCRIPT) "</script" else "</style"
        if (!lower.contains(closeTag)) {
            return if (state.markupEmbedded == MarkupEmbeddedMode.SCRIPT) {
                highlightCodeSpecLine(line, JS_TS_SPEC, state)
            } else {
                highlightCssLine(line, state)
            }
        }
        state.markupEmbedded = MarkupEmbeddedMode.NONE
        state.blockMode = BlockMode.NONE
    }

    return buildAnnotatedString {
        var i = 0
        val n = line.length
        if (state.blockMode == BlockMode.HTML_COMMENT) {
            i = consumeContinuedBlock(line, 0, state)
        }

        while (i < n) {
            if (state.inMarkupTag) {
                i = consumeMarkupTagAttributes(line, i, state)
                continue
            }

            if (line.startsWith("<!--", i)) {
                val close = line.indexOf("-->", i + 4)
                if (close >= 0) {
                    withStyle(SyntaxStyles.Comment) { append(line.substring(i, close + 3)) }
                    i = close + 3
                } else {
                    withStyle(SyntaxStyles.Comment) { append(line.substring(i)) }
                    state.blockMode = BlockMode.HTML_COMMENT
                    return@buildAnnotatedString
                }
                continue
            }

            if (line.startsWith("<!", i) || line.startsWith("<?", i)) {
                val close = line.indexOf('>', i + 2)
                val end = if (close >= 0) close + 1 else n
                withStyle(SyntaxStyles.Directive) { append(line.substring(i, end)) }
                i = end
                continue
            }

            if (line[i] == '<' && i + 1 < n && (line[i + 1].isLetter() || line[i + 1] == '/' || line[i + 1] == '_')) {
                val isClosing = line[i + 1] == '/'
                val nameStart = if (isClosing) i + 2 else i + 1
                var nameEnd = nameStart
                while (nameEnd < n && (line[nameEnd].isLetterOrDigit() || line[nameEnd] in charArrayOf('-', '_', ':', '.'))) {
                    nameEnd++
                }
                val tagName = line.substring(nameStart, nameEnd)
                withStyle(SyntaxStyles.Punctuation) { append(if (isClosing) "</" else "<") }
                if (tagName.isNotEmpty()) {
                    withStyle(SyntaxStyles.Keyword) { append(tagName) }
                }
                i = nameEnd
                state.inMarkupTag = true
                i = consumeMarkupTagAttributes(line, i, state)
                if (allowEmbedded && !isClosing && !state.inMarkupTag) {
                    val lowerTag = tagName.lowercase()
                    val selfClosed = i >= 2 && line[i - 2] == '/' && line[i - 1] == '>'
                    if (!selfClosed) {
                        val restLower = line.substring(i).lowercase()
                        if (lowerTag == "script" && !restLower.contains("</script")) {
                            state.markupEmbedded = MarkupEmbeddedMode.SCRIPT
                        } else if (lowerTag == "style" && !restLower.contains("</style")) {
                            state.markupEmbedded = MarkupEmbeddedMode.STYLE
                        }
                    }
                }
                continue
            }

            if (line[i] == '&') {
                val semi = line.indexOf(';', i + 1)
                if (semi > i + 1 && semi - i <= 12) {
                    withStyle(SyntaxStyles.Escape) { append(line.substring(i, semi + 1)) }
                    i = semi + 1
                    continue
                }
            }

            append(line[i])
            i++
        }
    }
}

private fun AnnotatedString.Builder.consumeMarkupTagAttributes(
    line: String,
    start: Int,
    state: LexerState,
): Int {
    var i = start
    val n = line.length
    while (i < n) {
        val c = line[i]
        if (c == '>') {
            withStyle(SyntaxStyles.Punctuation) { append('>') }
            state.inMarkupTag = false
            return i + 1
        }
        if (c == '/' && i + 1 < n && line[i + 1] == '>') {
            withStyle(SyntaxStyles.Punctuation) { append("/>") }
            state.inMarkupTag = false
            return i + 2
        }
        if (c == '"' || c == '\'') {
            i = appendQuotedString(line, i, c, sqlEscape = false)
            continue
        }
        if (c == '=') {
            withStyle(SyntaxStyles.Punctuation) { append('=') }
            i++
            continue
        }
        if (c.isLetter() || c in charArrayOf('@', ':', '_', '-')) {
            var end = i + 1
            while (end < n && (line[end].isLetterOrDigit() || line[end] in charArrayOf('-', '_', ':', '.', '@'))) end++
            withStyle(SyntaxStyles.Type) { append(line.substring(i, end)) }
            i = end
            continue
        }
        append(c)
        i++
    }
    return n
}

// ---------------------------------------------------------------------------
// 6. CSS / SCSS / LESS Lexer
// ---------------------------------------------------------------------------

private fun highlightCssLine(line: String, state: LexerState): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = line.length
    if (state.blockMode == BlockMode.BLOCK_COMMENT) {
        i = consumeContinuedBlock(line, 0, state)
    }

    while (i < n) {
        val c = line[i]
        if (c == '/' && i + 1 < n && line[i + 1] == '*') {
            val close = line.indexOf("*/", i + 2)
            if (close >= 0) {
                withStyle(SyntaxStyles.Comment) { append(line.substring(i, close + 2)) }
                i = close + 2
            } else {
                withStyle(SyntaxStyles.Comment) { append(line.substring(i)) }
                state.blockMode = BlockMode.BLOCK_COMMENT
                return@buildAnnotatedString
            }
            continue
        }
        if (c == '/' && i + 1 < n && line[i + 1] == '/') {
            withStyle(SyntaxStyles.Comment) { append(line.substring(i)) }
            return@buildAnnotatedString
        }
        if (c == '"' || c == '\'') {
            i = appendQuotedString(line, i, c, sqlEscape = false)
            continue
        }
        if (c == '@' && i + 1 < n && line[i + 1].isLetter()) {
            var end = i + 1
            while (end < n && (line[end].isLetterOrDigit() || line[end] == '-')) end++
            withStyle(SyntaxStyles.Keyword) { append(line.substring(i, end)) }
            i = end
            continue
        }
        if (c == '!' && line.startsWith("!important", i, ignoreCase = true)) {
            withStyle(SyntaxStyles.Keyword) { append(line.substring(i, i + 10)) }
            i += 10
            continue
        }
        if (c == '#' && i + 1 < n && (line[i + 1].isLetterOrDigit() || line[i + 1] == '-' || line[i + 1] == '_')) {
            var end = i + 1
            while (end < n && (line[end].isLetterOrDigit() || line[end] == '-' || line[end] == '_')) end++
            val tok = line.substring(i, end)
            val isHexColor = (tok.length == 4 || tok.length == 7 || tok.length == 9) &&
                tok.drop(1).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            withStyle(if (isHexColor) SyntaxStyles.Number else SyntaxStyles.Type) { append(tok) }
            i = end
            continue
        }
        if (c == '-' && i + 2 < n && line[i + 1] == '-' && (line[i + 2].isLetterOrDigit() || line[i + 2] == '_')) {
            var end = i + 2
            while (end < n && (line[end].isLetterOrDigit() || line[end] == '-' || line[end] == '_')) end++
            withStyle(SyntaxStyles.Property) { append(line.substring(i, end)) }
            i = end
            continue
        }
        if (c == '.' && i + 1 < n && (line[i + 1].isLetter() || line[i + 1] == '_' || line[i + 1] == '-')) {
            var end = i + 1
            while (end < n && (line[end].isLetterOrDigit() || line[end] == '-' || line[end] == '_')) end++
            withStyle(SyntaxStyles.Type) { append(line.substring(i, end)) }
            i = end
            continue
        }
        if (c.isDigit() && (i == 0 || !line[i - 1].isLetterOrDigit())) {
            var end = i + 1
            while (end < n && (line[end].isDigit() || line[end] == '.')) end++
            while (end < n && (line[end].isLetter() || line[end] == '%')) end++
            withStyle(SyntaxStyles.Number) { append(line.substring(i, end)) }
            i = end
            continue
        }
        if (c.isLetter() || c == '_') {
            var end = i + 1
            while (end < n && (line[end].isLetterOrDigit() || line[end] == '-' || line[end] == '_')) end++
            val word = line.substring(i, end)
            val style = when {
                end < n && line[end] == '(' -> SyntaxStyles.Function
                end < n && line[end] == ':' && (end + 1 == n || line[end + 1] != ':') -> SyntaxStyles.Property
                else -> SyntaxStyles.Plain
            }
            withStyle(style) { append(word) }
            i = end
            continue
        }
        append(c)
        i++
    }
}

// ---------------------------------------------------------------------------
// 7. Shell / Dockerfile / Makefile / Markdown Source / Diff / Log Lexers
// ---------------------------------------------------------------------------

private val SHELL_KEYWORDS = setOf(
    "if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while", "until",
    "case", "esac", "function", "select", "export", "local", "readonly", "declare",
    "typeset", "unset", "return", "exit", "break", "continue", "source", "eval", "exec", "set", "trap",
)

private fun highlightShellLine(line: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = line.length
    if (line.startsWith("#!")) {
        withStyle(SyntaxStyles.Directive) { append(line) }
        return@buildAnnotatedString
    }
    var expectCmd = true
    while (i < n) {
        val c = line[i]
        if (c.isWhitespace()) {
            append(c)
            i++
            continue
        }
        if (c == '#' && (i == 0 || line[i - 1].isWhitespace())) {
            withStyle(SyntaxStyles.Comment) { append(line.substring(i)) }
            return@buildAnnotatedString
        }
        if (c == '\'' || c == '"') {
            i = appendQuotedString(line, i, c, sqlEscape = false)
            expectCmd = false
            continue
        }
        if (c == '$') {
            var end = i + 1
            if (end < n && line[end] == '{') {
                val close = line.indexOf('}', end + 1)
                end = if (close >= 0) close + 1 else n
            } else if (end < n && (line[end] == '(' || line[end] in charArrayOf('?', '$', '!', '#', '@', '*'))) {
                end++
            } else {
                while (end < n && (line[end].isLetterOrDigit() || line[end] == '_')) end++
            }
            withStyle(SyntaxStyles.Property) { append(line.substring(i, end)) }
            expectCmd = false
            i = end
            continue
        }
        if (c in charArrayOf('|', '&', ';', '>', '<')) {
            var end = i + 1
            while (end < n && line[end] in charArrayOf('|', '&', '>', '<')) end++
            val op = line.substring(i, end)
            withStyle(SyntaxStyles.Operator) { append(op) }
            if (op.contains('|') || op.contains('&') || op.contains(';')) expectCmd = true
            i = end
            continue
        }
        var end = i
        while (end < n && !line[end].isWhitespace() && line[end] !in charArrayOf('|', '&', ';', '>', '<', '\'', '"', '$')) {
            end++
        }
        val token = line.substring(i, end)
        val eqIdx = token.indexOf('=')
        if (expectCmd && eqIdx > 0 && !token.startsWith('-') && token.substring(0, eqIdx).all { isIdentPart(it) }) {
            withStyle(SyntaxStyles.Property) { append(token.substring(0, eqIdx)) }
            withStyle(SyntaxStyles.Operator) { append('=') }
            withStyle(SyntaxStyles.StringLit) { append(token.substring(eqIdx + 1)) }
        } else {
            val style = when {
                token in SHELL_KEYWORDS -> {
                    expectCmd = token in setOf("then", "else", "do", "elif")
                    SyntaxStyles.Keyword
                }
                expectCmd -> {
                    if (token !in setOf("sudo", "env", "time", "nohup", "command")) expectCmd = false
                    SyntaxStyles.Function
                }
                token.startsWith("-") -> SyntaxStyles.Type
                isSimpleNumber(token) -> SyntaxStyles.Number
                else -> SyntaxStyles.Plain
            }
            withStyle(style) { append(token) }
        }
        i = end
    }
}

private val DOCKERFILE_INSTRUCTIONS = setOf(
    "FROM", "RUN", "CMD", "LABEL", "MAINTAINER", "EXPOSE", "ENV", "ADD", "COPY",
    "ENTRYPOINT", "VOLUME", "USER", "WORKDIR", "ARG", "ONBUILD", "STOPSIGNAL", "HEALTHCHECK", "SHELL",
)

private fun highlightDockerfileLine(line: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = line.length
    while (i < n && (line[i] == ' ' || line[i] == '\t')) {
        append(line[i])
        i++
    }
    if (i >= n) return@buildAnnotatedString
    if (line[i] == '#') {
        withStyle(SyntaxStyles.Comment) { append(line.substring(i)) }
        return@buildAnnotatedString
    }
    var end = i
    while (end < n && line[end].isLetter()) end++
    val first = line.substring(i, end)
    if (first.uppercase() in DOCKERFILE_INSTRUCTIONS && (end == n || line[end].isWhitespace())) {
        withStyle(SyntaxStyles.Keyword) { append(first) }
        i = end
    }
    if (i < n) {
        append(highlightShellLine(line.substring(i)))
    }
}

private val MAKEFILE_DIRECTIVES = setOf(
    "ifeq", "ifneq", "ifdef", "ifndef", "else", "endif", "include", "-include",
    "sinclude", "export", "unexport", "override", "define", "endef", "vpath",
)

private fun highlightMakefileLine(line: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = line.length
    if (line.startsWith("\t")) {
        append('\t')
        append(highlightShellLine(line.substring(1)))
        return@buildAnnotatedString
    }
    while (i < n && line[i] == ' ') {
        append(' ')
        i++
    }
    if (i >= n) return@buildAnnotatedString
    if (line[i] == '#') {
        withStyle(SyntaxStyles.Comment) { append(line.substring(i)) }
        return@buildAnnotatedString
    }
    // Check directive
    var wEnd = i
    while (wEnd < n && !line[wEnd].isWhitespace()) wEnd++
    val firstWord = line.substring(i, wEnd)
    if (firstWord in MAKEFILE_DIRECTIVES) {
        withStyle(SyntaxStyles.Keyword) { append(firstWord) }
        if (wEnd < n) append(line.substring(wEnd))
        return@buildAnnotatedString
    }
    // Check target `target:` vs assignment `VAR := val`
    val colonIdx = line.indexOf(':', i)
    val eqIdx = line.indexOf('=', i)
    if (eqIdx > i && (colonIdx < 0 || eqIdx <= colonIdx + 1)) {
        val opStart = if (eqIdx > i && line[eqIdx - 1] in charArrayOf(':', '?', '+', '!')) eqIdx - 1 else eqIdx
        withStyle(SyntaxStyles.Property) { append(line.substring(i, opStart)) }
        withStyle(SyntaxStyles.Operator) { append(line.substring(opStart, eqIdx + 1)) }
        i = eqIdx + 1
    } else if (colonIdx > i && !firstWord.contains('$')) {
        val targetStyle = if (firstWord.startsWith(".")) SyntaxStyles.Directive else SyntaxStyles.Function
        withStyle(targetStyle) { append(line.substring(i, colonIdx)) }
        withStyle(SyntaxStyles.Operator) { append(':') }
        i = colonIdx + 1
    }
    while (i < n) {
        val c = line[i]
        if (c == '#') {
            withStyle(SyntaxStyles.Comment) { append(line.substring(i)) }
            return@buildAnnotatedString
        }
        if (c == '$' && i + 1 < n && (line[i + 1] == '(' || line[i + 1] == '{')) {
            val closeChar = if (line[i + 1] == '(') ')' else '}'
            val close = line.indexOf(closeChar, i + 2)
            val end = if (close >= 0) close + 1 else n
            withStyle(SyntaxStyles.Property) { append(line.substring(i, end)) }
            i = end
            continue
        }
        append(c)
        i++
    }
}

private fun highlightMarkdownSourceLine(line: String, state: LexerState): AnnotatedString {
    val trimmed = line.trimStart()
    if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
        val delim = trimmed.take(3)
        if (!state.inMarkdownFence) {
            state.inMarkdownFence = true
            state.markdownFenceDelim = delim
            state.markdownFenceLang = trimmed.drop(3).trim().substringBefore(' ').lowercase()
            return buildAnnotatedString {
                withStyle(SyntaxStyles.Directive) { append(line) }
            }
        } else if (delim == state.markdownFenceDelim) {
            state.inMarkdownFence = false
            state.markdownFenceLang = ""
            state.markdownFenceDelim = ""
            state.blockMode = BlockMode.NONE
            return buildAnnotatedString {
                withStyle(SyntaxStyles.Directive) { append(line) }
            }
        }
    }

    if (state.inMarkdownFence && state.markdownFenceLang.isNotEmpty()) {
        val innerLang = detectCodeLanguage(state.markdownFenceLang)
        if (innerLang.id != "plain" && innerLang.id != "markdown") {
            return highlightSingleLineWithState(line, innerLang.id, state)
        }
    }

    return buildAnnotatedString {
        if (trimmed.startsWith("#")) {
            val hashes = trimmed.takeWhile { it == '#' }.length
            if (hashes in 1..6 && (hashes == trimmed.length || trimmed[hashes] == ' ')) {
                withStyle(SyntaxStyles.Heading) { append(line) }
                return@buildAnnotatedString
            }
        }
        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            withStyle(SyntaxStyles.Punctuation) { append(line) }
            return@buildAnnotatedString
        }
        if (trimmed.startsWith(">")) {
            withStyle(SyntaxStyles.Comment) { append(line) }
            return@buildAnnotatedString
        }

        var i = 0
        val n = line.length
        while (i < n) {
            val c = line[i]
            if (c == '`') {
                val close = line.indexOf('`', i + 1)
                if (close > i) {
                    withStyle(SyntaxStyles.StringLit) { append(line.substring(i, close + 1)) }
                    i = close + 1
                    continue
                }
            }
            if (line.startsWith("**", i)) {
                val close = line.indexOf("**", i + 2)
                if (close > i + 2) {
                    withStyle(SyntaxStyles.Heading) { append(line.substring(i, close + 2)) }
                    i = close + 2
                    continue
                }
            }
            if (c == '[') {
                val closeBracket = line.indexOf(']', i + 1)
                if (closeBracket > i + 1 && closeBracket + 1 < n && line[closeBracket + 1] == '(') {
                    val closeParen = line.indexOf(')', closeBracket + 2)
                    if (closeParen > closeBracket + 2) {
                        withStyle(SyntaxStyles.Function) { append(line.substring(i, closeBracket + 1)) }
                        withStyle(SyntaxStyles.Property) { append(line.substring(closeBracket + 1, closeParen + 1)) }
                        i = closeParen + 1
                        continue
                    }
                }
            }
            append(c)
            i++
        }
    }
}

private fun highlightDiffLine(line: String): AnnotatedString = buildAnnotatedString {
    val style = when {
        line.startsWith("diff --git") || line.startsWith("index ") -> SyntaxStyles.Type
        line.startsWith("+++") || line.startsWith("---") -> SyntaxStyles.Directive
        line.startsWith("@@") -> SyntaxStyles.DiffHunk
        line.startsWith("+") -> SyntaxStyles.DiffAdd
        line.startsWith("-") -> SyntaxStyles.DiffDel
        else -> SyntaxStyles.Plain
    }
    withStyle(style) { append(line) }
}

private fun highlightLogLine(line: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = line.length
    while (i < n) {
        val c = line[i]
        if (c == '"' || c == '\'') {
            i = appendQuotedString(line, i, c, sqlEscape = false)
            continue
        }
        if (c.isLetter()) {
            var end = i + 1
            while (end < n && isIdentPart(line[end])) end++
            val word = line.substring(i, end)
            val style = when (word.uppercase()) {
                "ERROR", "FATAL", "PANIC", "CRITICAL", "FAIL", "FAILED" -> SyntaxStyles.Keyword
                "WARN", "WARNING" -> SyntaxStyles.Type
                "INFO", "OK", "SUCCESS", "PASSED" -> SyntaxStyles.Function
                "DEBUG", "TRACE" -> SyntaxStyles.Comment
                else -> SyntaxStyles.Plain
            }
            withStyle(style) { append(word) }
            i = end
            continue
        }
        append(c)
        i++
    }
}
