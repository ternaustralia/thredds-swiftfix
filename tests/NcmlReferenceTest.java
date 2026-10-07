import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import ucar.ma2.Array;
import ucar.ma2.DataType;
import ucar.nc2.dataset.NetcdfDataset;
import ucar.nc2.internal.ncml.NcmlReader;
import ucar.nc2.util.URLnaming;
import ucar.nc2.write.NetcdfFormatWriter;

/** Checks absolute and relative references in NcML documents stored in an object store. */
public final class NcmlReferenceTest {
  private static final String BASE = "cdms3://profile@localhost/bucket?site/wrapper.ncml#delimiter=/";
  private static final String NAMESPACE = "http://www.unidata.ucar.edu/namespaces/netcdf/ncml-2.2";

  /**
   * Run reference checks and read HTTP data through NcML with a cdms3 document location.
   *
   * @param args unused command-line arguments
   * @throws Exception if fixture creation, reference resolution or data reading fails
   */
  public static void main(String[] args) throws Exception {
    for (String reference : new String[] {
        "http://localhost/data.nc",
        "https://localhost/data.nc?selection=time#fragment",
        "dods://localhost/thredds/dodsC/data.nc",
        "dap4://localhost/thredds/dap4/data.nc",
        "cdms3://profile@localhost/other-bucket?data.nc#delimiter=/",
        "file:/tmp/data.nc"
    }) {
      assertResolution(BASE, reference, reference);
      assertFileResolution(BASE, reference, reference);
    }
    assertResolution(BASE, "data.nc", "cdms3://profile@localhost/bucket?site/data.nc#delimiter=/");
    assertResolution(BASE, "nested/data.nc", "cdms3://profile@localhost/bucket?site/nested/data.nc#delimiter=/");
    assertFileResolution(BASE, "data.nc", "cdms3://profile@localhost/bucket?site/data.nc#delimiter=/");
    assertFileResolution(BASE, "nested/data.nc", "cdms3://profile@localhost/bucket?site/nested/data.nc#delimiter=/");
    assertResolution(BASE, null, null);
    assertResolution(null, "data.nc", "data.nc");
    assertFileResolution(BASE, null, null);
    assertFileResolution(null, "data.nc", "data.nc");
    assertResolution("file:/tmp/wrapper.ncml", "data.nc", "file:/tmp/data.nc");
    assertResolution("https://localhost/site/wrapper.ncml", "data.nc", "https://localhost/site/data.nc");

    Path fixture = Files.createTempFile("ncml-reference-", ".nc");
    HttpServer server = null;
    try {
      NetcdfFormatWriter.Builder builder = NetcdfFormatWriter.createNewNetcdf3(fixture.toString());
      builder.addDimension("time", 2);
      builder.addVariable("time", DataType.DOUBLE, "time");
      try (NetcdfFormatWriter writer = builder.build()) {
        writer.write("time", Array.factory(DataType.DOUBLE, new int[] {2}, new double[] {1.0, 2.0}));
      }
      byte[] contents = Files.readAllBytes(fixture);
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/data.nc", exchange -> serveFixture(exchange, contents));
      server.start();
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/data.nc";

      String wrapper = "<netcdf xmlns=\"" + NAMESPACE + "\" location=\"" + url + "\"/>";
      assertNcmlData(wrapper, new double[] {1.0, 2.0});
      String aggregation = "<netcdf xmlns=\"" + NAMESPACE + "\">"
          + "<aggregation dimName=\"time\" type=\"joinExisting\">"
          + "<netcdf location=\"" + url + "\" ncoords=\"2\"/>"
          + "<netcdf location=\"" + url + "\" ncoords=\"2\"/>"
          + "</aggregation></netcdf>";
      assertNcmlData(aggregation, new double[] {1.0, 2.0, 1.0, 2.0});
    } finally {
      if (server != null) {
        server.stop(0);
      }
      Files.deleteIfExists(fixture);
    }
    System.out.println("NcML reference resolution, wrapper and aggregation checks passed");
  }

  /**
   * Check the resolved location without opening the referenced object.
   *
   * @param base document location, or null when no base is available
   * @param reference absolute URL or relative object key, or null
   * @param expected resolved location, or null
   */
  private static void assertResolution(String base, String reference, String expected) {
    String actual = URLnaming.resolve(base, reference);
    if (!Objects.equals(expected, actual)) {
      throw new AssertionError("Reference " + reference + " resolved to " + actual + " instead of " + expected);
    }
  }

  /**
   * Check the location used for explicit members of an NcML aggregation.
   *
   * @param base document location, or null when no base is available
   * @param reference absolute URL or relative object key, or null
   * @param expected resolved location, or null
   */
  private static void assertFileResolution(String base, String reference, String expected) {
    String actual = URLnaming.resolveFile(base, reference);
    if (!Objects.equals(expected, actual)) {
      throw new AssertionError("Aggregation reference " + reference + " resolved to " + actual + " instead of " + expected);
    }
  }

  /**
   * Read values through an NcML document whose base is an object-store location.
   *
   * @param ncml document containing absolute HTTP references to the NetCDF fixture
   * @param expected complete time values expected from the wrapper or aggregation
   * @throws Exception if the document or referenced data cannot be read
   */
  private static void assertNcmlData(String ncml, double[] expected) throws Exception {
    try (NetcdfDataset dataset = NcmlReader.readNcml(new StringReader(ncml), BASE, null).build()) {
      Array values = dataset.findVariable("time").read();
      if (values.getSize() != expected.length) {
        throw new AssertionError("Unexpected time dimension length " + values.getSize());
      }
      for (int i = 0; i < expected.length; i++) {
        if (values.getDouble(i) != expected[i]) {
          throw new AssertionError("Unexpected time value at index " + i);
        }
      }
    }
  }

  /**
   * Serve the fixture with the HEAD and byte-range operations used by NetCDF-Java.
   *
   * @param exchange request for the test NetCDF file
   * @param contents complete bytes of the generated NetCDF fixture
   * @throws IOException if the response cannot be written
   */
  private static void serveFixture(HttpExchange exchange, byte[] contents) throws IOException {
    try {
      exchange.getResponseHeaders().set("Content-Type", "application/x-netcdf");
      exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
      if (exchange.getRequestMethod().equals("HEAD")) {
        exchange.getResponseHeaders().set("Content-Length", Integer.toString(contents.length));
        exchange.sendResponseHeaders(200, -1);
        return;
      }
      String range = exchange.getRequestHeaders().getFirst("Range");
      int start = 0;
      int end = contents.length - 1;
      if (range != null) {
        String[] bounds = range.substring("bytes=".length()).split("-", -1);
        start = Integer.parseInt(bounds[0]);
        if (!bounds[1].isEmpty()) {
          end = Math.min(end, Integer.parseInt(bounds[1]));
        }
        if (start > end) {
          exchange.getResponseHeaders().set("Content-Range", "bytes */" + contents.length);
          exchange.sendResponseHeaders(416, -1);
          return;
        }
        exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + contents.length);
      }
      exchange.sendResponseHeaders(range == null ? 200 : 206, end - start + 1);
      exchange.getResponseBody().write(contents, start, end - start + 1);
    } finally {
      exchange.close();
    }
  }
}
