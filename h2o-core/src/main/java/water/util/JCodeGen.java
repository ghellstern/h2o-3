package water.util;

import java.io.*;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.*;
import javax.tools.*;
import javax.tools.JavaCompiler.CompilationTask;
import javax.tools.JavaFileObject.Kind;
import water.H2O;

/** Internal utility for pretty-printing Models as Java code
 */
public class JCodeGen {

  public static SB toStaticVar(SB sb, String varname, int value, String comment) {
    if (comment!=null) sb.ip("// ").p(comment).nl();
    return sb.ip("public static final int ").p(varname).p(" = ").p(value).p(';').nl();
  }

  public static SB toStaticVar(SB sb, String varname, String[] values, String comment) {
    if (comment!=null) sb.ip("// ").p(comment).nl();
    sb.ip("public static final String[] ").p(varname).p(" = ");
    if (values == null) return sb.p("null;").nl();
    sb.p("new String[]{").p("\""+values[0]+"\"");
    for (int i = 1; i < values.length; ++i) sb.p(",").p("\""+values[i]+"\"");
    return sb.p("};").nl();
  }

  public static SB toStaticVar(SB sb, String varname, float[] values, String comment) {
    if (comment!=null) sb.ip("// ").p(comment).nl();
    sb.ip("public static final float[] ").p(varname).p(" = ");
    if (values == null) return sb.p("null;").nl();
    sb.p("{").pj(values[0]);
    for (int i = 1; i < values.length; ++i) sb.p(",").pj(values[i]);
    return sb.p("};").nl();
  }

  public static SB toStaticVar(SB sb, String varname, double[][] values, String comment) {
    if (comment!=null) sb.ip("// ").p(comment).nl();
    sb.ip("public static final double[][] ").p(varname).p(" = ");
    return sb.toJavaStringInit(values).p(';').nl();
  }

  /**
   * Generates a new class with one static member called <em>VALUES</em> which
   * is filled by values of given array.
   * <p>The generator can generate more classes to avoid limit of class constant
   * pool holding all generated literals</p>.
   *
   * @param sb output
   * @param className name of generated class
   * @param values array holding values which should be hold in generated field VALUES.
   * @return output buffer
   */
  public static SB toClassWithArray(SB sb, String modifiers, String className, String[] values) {
    sb.ip(modifiers!=null ? modifiers+" ": "").p("class ").p(className).p(" {").nl().ii(1);
    sb.ip("public static final String[] VALUES = ");
    if (values==null)
      sb.p("null;").nl();
    else {
      sb.p("new String[").p(values.length).p("];").nl();

      // Static part
      int s = 0;
      int remain = values.length;
      int its = 0;
      SB sb4fillers = new SB().ci(sb);
      sb.ip("static {").ii(1).nl();
      while (remain>0) {
          String subClzName = className + "_" + its++;
          int len = Math.min(MAX_STRINGS_IN_CONST_POOL, remain);
          toClassWithArrayFill(sb4fillers, subClzName, values, s, len);
          sb.ip(subClzName).p(".fill(VALUES);").nl();
          s += len;
          remain -= len;
      }
      sb.di(1).ip("}").nl();
      sb.p(sb4fillers);
    }
    return sb.di(1).p("}").nl();
  }

  /** Maximum number of string generated per class (static initializer) */
  public static int MAX_STRINGS_IN_CONST_POOL = 3000;

  public static SB toClassWithArrayFill(SB sb, String clzName, String[] values, int start, int len) {
    sb.ip("static final class ").p(clzName).p(" {").ii(1).nl();
    sb.ip("static final void fill(String[] sa) {").ii(1).nl();
    for (int i=0; i<len; i++) {
      sb.ip("sa[").p(start+i).p("] = ").ps(values[start+i]).p(";").nl();
    }
    sb.di(1).ip("}").nl();
    sb.di(1).ip("}").nl();
    return sb;
  }

  /** Transform given string to legal java Identifier (see Java grammar http://docs.oracle.com/javase/specs/jls/se7/html/jls-3.html#jls-3.8) */
  public static String toJavaId(String s) {
    // Note that the leading 4 backslashes turn into 2 backslashes in the
    // string - which turn into a single backslash in the REGEXP.
    // "+-*/ !@#$%^&()={}[]|\\;:'\"<>,.?/"
    return s.replaceAll("[+\\-* !@#$%^&()={}\\[\\]|;:'\"<>,.?/]",  "_");
  }

  
  public static Class compile(String class_name, String java_text) throws Exception {
    // Wrap input string up as a file-like java source thing
    JavaFileObject file = new JavaSourceFromString(class_name, java_text);
    // Capture all output class "files" as simple ByteArrayOutputStreams
    JavacFileManager jfm = new JavacFileManager(COMPILER.getStandardFileManager(null, null, null));
    // Invoke javac
    if( !COMPILER.getTask(null, jfm, null, /*javac options*/null, null, Arrays.asList(file)).call() )
      throw H2O.fail("Internal POJO compilation failed.");

    // Compiled; now load all classes.  Our single POJO file actually makes a
    // bunch of classes to keep each class constant pool size reasonable.
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    for( Map.Entry<String, ByteArrayOutputStream> entry : jfm._buffers.entrySet()) {
      byte[] bits = entry.getValue().toByteArray();
      // Call classLoader.defineClass("className",byte[])
      DEFINE_CLASS_METHOD.invoke(cl, entry.getKey(), bits, 0, bits.length);
    }
    return Class.forName(class_name); // Return the original top-level class
  }

  // Parts of this code are shamelessly robbed from:
  //   OpenHFT/Java-Runtime-Compiler/blob/master/compiler/src/main/java/net/openhft/compiler
  // Then a lot of extra stuff is tossed out.

  private static final Method DEFINE_CLASS_METHOD;
  private static final JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();
  static {
    try {
      DEFINE_CLASS_METHOD = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class);
      DEFINE_CLASS_METHOD.setAccessible(true);
    } catch (NoSuchMethodException e) {
      throw new AssertionError(e);
    }
  }

  // Simple declaration of a string as a file-like thing
  static class JavaSourceFromString extends javax.tools.SimpleJavaFileObject {
    final String _code;
    JavaSourceFromString(String name, String code) {
      super(URI.create("string:///" + name.replace('.','/') + Kind.SOURCE.extension),Kind.SOURCE);
      _code = code;
    }
    @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return _code; }
  }

  // Manage all "files" being manipulated by javac - the input files are really
  // Strings, the output files are simple byte[]'s holding the classes.  Things
  // other than Java source strings are routed through the standard fileManager
  // so javac can look up related class files.
  static class JavacFileManager implements JavaFileManager {
    private final StandardJavaFileManager _fileManager;
    final HashMap<String, ByteArrayOutputStream> _buffers = new HashMap<>();
    JavacFileManager(StandardJavaFileManager fileManager) { _fileManager = fileManager; }
    public ClassLoader getClassLoader(Location location) { return _fileManager.getClassLoader(location);  }
    public Iterable<JavaFileObject> list(Location location, String packageName, Set<Kind> kinds, boolean recurse) throws IOException {
      return _fileManager.list(location, packageName, kinds, recurse);
    }
    public String inferBinaryName(Location location, JavaFileObject file) { return _fileManager.inferBinaryName(location, file); }
    public boolean isSameFile(FileObject a, FileObject b) { return _fileManager.isSameFile(a, b); }
    public boolean handleOption(String current, Iterator<String> remaining) { return _fileManager.handleOption(current, remaining); }
    public boolean hasLocation(Location location) { return _fileManager.hasLocation(location); }
    public JavaFileObject getJavaFileForInput(Location location, String className, Kind kind) throws IOException {
      if( location == StandardLocation.CLASS_OUTPUT && _buffers.containsKey(className) && kind == Kind.CLASS ) {
        final byte[] bytes = _buffers.get(className).toByteArray();
        return new SimpleJavaFileObject(URI.create(className), kind) {
          public InputStream openInputStream() {
            return new ByteArrayInputStream(bytes);
          }
        };
      }
      return _fileManager.getJavaFileForInput(location, className, kind);
    }
    public JavaFileObject getJavaFileForOutput(Location location, final String className, Kind kind, FileObject sibling) throws IOException {
      return new SimpleJavaFileObject(URI.create(className), kind) {
        public OutputStream openOutputStream() {
          ByteArrayOutputStream baos = new ByteArrayOutputStream();
          _buffers.put(className, baos);
          return baos;
        }
      };
    }
    public FileObject getFileForInput(Location location, String packageName, String relativeName) throws IOException {
      return _fileManager.getFileForInput(location, packageName, relativeName);
    }
    public FileObject getFileForOutput(Location location, String packageName, String relativeName, FileObject sibling) throws IOException {
      return _fileManager.getFileForOutput(location, packageName, relativeName, sibling);
    }
    public void flush() throws IOException { _fileManager.flush(); }
    public void close() throws IOException { _fileManager.close(); }
    public int isSupportedOption(String option) { return _fileManager.isSupportedOption(option); }
  }
}

