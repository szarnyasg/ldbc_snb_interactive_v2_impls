/**
 *
 */
package com.ldbc.snb.janusgraph.importers;

import com.ldbc.snb.janusgraph.importers.utils.LoadingStats;
import com.ldbc.snb.janusgraph.importers.utils.PoolThread;
import com.ldbc.snb.janusgraph.importers.utils.StatsReportingThread;
import com.ldbc.snb.janusgraph.importers.utils.ThreadPool;
import com.netflix.astyanax.connectionpool.exceptions.ConnectionException;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.janusgraph.core.*;
import org.janusgraph.core.schema.JanusGraphManagement;
import org.janusgraph.core.schema.SchemaAction;
import org.janusgraph.core.schema.SchemaStatus;
import org.janusgraph.graphdb.database.StandardJanusGraph;
import org.janusgraph.graphdb.database.management.ManagementSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.directory.SchemaViolationException;
import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;

/**
 * @author Tomer Sagi
 *         <p/>
 *         Importer for a titan database
 */
public class JanusGraphImporter {

    public final static String CSVSPLIT = "\\|";
    public final static String TUPLESPLIT = "\\.";
    private static Class<?>[] VALID_CLASSES = {Integer.class, Long.class, String.class, Date.class, BigDecimal.class, Double.class, BigInteger.class};
    private StandardJanusGraph graph;
    private WorkloadEnum workload;
    private Logger logger = LoggerFactory.getLogger("org.janusgraph");
    private ArrayList<String> vertexIndexes = new ArrayList<String>();
    private JanusGraphImporterConfig config;

    /* (non-Javadoc)
     * @see hpl.alp2.titan.importers.DBgenImporter#init(java.lang.String)
     */
    public void init(String connectionURL, WorkloadEnum workload, JanusGraphImporterConfig config) throws ConnectionException {
        logger.info("entered init");
        graph = (StandardJanusGraph)JanusGraphFactory.open(connectionURL);
        this.workload = workload;
        this.config = config;
        System.out.println("Connected");
        if (!buildSchema(workload.getSchema())) {
            logger.error("failed to create schema");
        }
        logger.info("Completed init");
    }

    /**
     * Builds interactive workload schema in database
     *
     * @return true if building the schema succeeded
     */
    private boolean buildSchema(WorkLoadSchema s) {
        logger.info("Building Schema");
        JanusGraphManagement management;
        //Create Vertex Labels and assign id suffix
        Set<String> vTypes = s.getVertexTypes().keySet();
        for (String v : vTypes) {
            management = graph.openManagement();
            if (!graph.containsVertexLabel(v)) {
                management.makeVertexLabel(v).make();
                management.commit();
            }
        }

        logger.info("Creating Vertex Labels");
        for (String e : s.getEdgeTypes()) {
            management = graph.openManagement();
            if (!graph.containsRelationType(e)) {
                management.makeEdgeLabel(e).multiplicity(Multiplicity.SIMPLE).make();
                management.commit();
            }
        }

        logger.info("Creating edge labels");
        //Create Vertex Property Labels
        Set<Class<?>> allowed = new HashSet<>(Arrays.asList(VALID_CLASSES));
        for (String v : s.getVertexProperties().keySet()) {
            for (String p : s.getVertexProperties().get(v)) {
                String janusPropertyKey = v+"."+p;
                logger.info("Created Property Key "+janusPropertyKey);
                if (!graph.containsRelationType(p)) {
                    management = graph.openManagement();
                    Class<?> clazz = s.getVPropertyClass(v, p);
                    Cardinality c = (clazz.getSimpleName().equals("Arrays") ? Cardinality.LIST : Cardinality.SINGLE);
                    if (clazz.equals(Arrays.class))
                        clazz = String.class;

                    PropertyKey pk;
                    //Date represented as long values
                    if (clazz.equals(Date.class))
                        pk = management.makePropertyKey(janusPropertyKey).dataType(Long.class).cardinality(c).make();
                    else
                        pk = management.makePropertyKey(janusPropertyKey).dataType(clazz).cardinality(c).make();

                    if (!allowed.contains(clazz)) {
                        logger.error("Class {} unsupported by backend index", clazz.getSimpleName());
                        continue;
                    }

                    if(p.compareTo("id") == 0 || p.compareTo("creationDate") == 0) {
                        management.buildIndex("by" + janusPropertyKey, Vertex.class).addKey(pk).buildCompositeIndex();
                        //vertexIndexes.add(janusPropertyKey);
                    }
                    management.commit();
                }
            }
        }

        logger.info("Creating edge property labels");
        for (String e : s.getEdgeProperties().keySet()) {
            for (String p : s.getEdgeProperties().get(e)) {

                String janusPropertyKey = e+"."+p;
                if (!graph.containsRelationType(p)) {
                    management = graph.openManagement();
                    Class<?> clazz = s.getEPropertyClass(e, p);
                    if (clazz.equals(Arrays.class))
                        clazz = String.class;
                    Cardinality c = (clazz.isArray() ? Cardinality.LIST : Cardinality.SINGLE);
                    PropertyKey pk;
                    //Date represented as long values
                    if (clazz.equals(Date.class))
                        pk = management.makePropertyKey(janusPropertyKey).dataType(Long.class).cardinality(c).make();
                    else
                        pk = management.makePropertyKey(janusPropertyKey).dataType(clazz).cardinality(c).make();

                    if (!allowed.contains(clazz)) {
                        logger.error("Class {} unsupported by backend index", clazz.getSimpleName());
                        continue;
                    }

                    if(p.compareTo("id") == 0 || p.compareTo("creationDate") == 0) {
                        management.buildIndex("by" + janusPropertyKey, Vertex.class).addKey(pk).buildCompositeIndex();
                    }
                    management.commit();
                }
            }
        }
        graph.tx().commit();
        logger.info("Finished schema creation");
        return true;
    }

    /* (non-Javadoc)
     * @see hpl.alp2.titan.importers.DBgenImporter#importData(java.io.File)
     */
    public boolean importData(File dir) throws IOException, SchemaViolationException {
        logger.info("entered import data, dir is: {}", dir.getAbsolutePath() );
        if (!dir.isDirectory())
            return false;

        LoadingStats stats = new LoadingStats();

        StatsReportingThread statsThread = new StatsReportingThread(stats,5000);
        statsThread.start();

        WorkLoadSchema s = this.workload.getSchema();
        Map<String, String> vpMap = s.getVPFileMap();
        Map<String, String> eMap = s.getEFileMap();

        loadVertices(dir, s.getVertexTypes().keySet(), stats);
        loadEdges(dir, eMap,stats);
        //loadVertexProperties(dir, vpMap, stats);
        logger.info("completed import data");
        try {
            statsThread.interrupt();
        } catch(Exception e) {

        }
        statsThread.interrupt();
        logger.info("Number of vertices loaded: {}. Number of edges loaded {}", stats.getNumVertices(), stats.getNumEdges());
        return true;
    }

    /**
     * Loads vertices and their properties from the csv files
     *
     * @param dir     Directory in which the files reside
     * @param vSet    Set pf expected vertex types
     * @throws IOException              if has trouble reading the file
     * @throws SchemaViolationException if file doesn't match the expected schema according to the workload definition
     */
    private void loadVertices(File dir, Set<String> vSet, LoadingStats stats)
            throws IOException, SchemaViolationException {
        logger.info("entered load vertices");

        List<VertexFileReadingTask> tasks = new ArrayList<VertexFileReadingTask>();
        WorkLoadSchema schema = this.workload.getSchema();

        ThreadPool vertexLoadingThreadPool = new ThreadPool(config.getNumThreads(),config.getNumThreads());

        for (final String vertexLabel : vSet) {
            HashSet<String> fileSet = new HashSet<>();
            fileSet.addAll(Arrays.asList(dir.list(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.matches(vertexLabel.toLowerCase() + "_\\d+_\\d+\\.csv");
                }
            })));
            for (String fileName : fileSet) {
                tasks.add(new VertexFileReadingTask(graph,schema,dir+"/"+fileName,vertexLabel,vertexLoadingThreadPool.getTaskQueue(),config.getTransactionSize(),stats));
            }
        }

        ThreadPool fileReadingThreadPool = new ThreadPool(1,tasks.size());
        for(VertexFileReadingTask task : tasks) {
            try {
                fileReadingThreadPool.execute(task);
            } catch(Exception e) {
                e.printStackTrace();
            }
        }

        try {
            fileReadingThreadPool.stop();
            vertexLoadingThreadPool.stop();
        } catch ( Exception e ) {
            e.printStackTrace();
        }
    }


    private void loadEdges(File dir, Map<String, String> eMap, LoadingStats stats)
            throws IOException, SchemaViolationException {
        logger.info("entered load edges");
        List<EdgeFileReadingTask> tasks = new ArrayList<EdgeFileReadingTask>();
        WorkLoadSchema schema = this.workload.getSchema();

        ThreadPool edgeLoadingThreadPool = new ThreadPool(config.getNumThreads(),config.getNumThreads());
        for (Map.Entry<String,String> ent : eMap.entrySet()) {
            HashSet<String> fileSet = new HashSet<>();
            final String fNamePrefix = ent.getValue();
            fileSet.addAll(Arrays.asList(dir.list(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.matches(fNamePrefix + "_\\d+_\\d+\\.csv");
                }
            })));
            for (String fileName : fileSet) {
                tasks.add(new EdgeFileReadingTask(graph,schema,dir+"/"+fileName,ent.getKey(),edgeLoadingThreadPool.getTaskQueue(),config.getTransactionSize(),stats));
            }
        }

        ThreadPool fileReadingThreadPool = new ThreadPool(1,tasks.size());
        for(EdgeFileReadingTask task : tasks) {
            try {
                fileReadingThreadPool.execute(task);
            } catch(Exception e) {
                e.printStackTrace();
            }
        }

        try {
            fileReadingThreadPool.stop();
            edgeLoadingThreadPool.stop();
        } catch ( Exception e ) {
            e.printStackTrace();
        }
    }

    /*
    private void loadVertexProperties(File dir, Map<String, String> vpMap)
            throws IOException, SchemaViolationException {
        logger.info("entered load VP");
        WorkLoadSchema s = this.workload.getSchema();

        for (Map.Entry<String,String> entry : vpMap.entrySet()) {
            HashSet<String> fileSet = new HashSet<>();
            final String fNameSuffix = entry.getValue();
            fileSet.addAll(Arrays.asList(dir.list(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.matches(fNameSuffix + "_\\d+_\\d+\\.csv");
                }
            })));
            for (String fName : fileSet) {
                logger.info("reading {}", fName);
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(
                                new FileInputStream(new File(dir, fName))
                                ,"UTF-8"));

                //Read title line and map to vertex properties, throw exception if doesn't match
                String line = br.readLine();
                if (line==null)
                    throw new IOException("Empty file" + fName);
                String[] header = line.split(CSVSPLIT);
                String vLabel = entry.getKey().split(TUPLESPLIT)[0];
                try {
                    validateVPHeader(s, vLabel, header);
                } catch (SchemaViolationException e) {
                    br.close();
                    throw e;
                }
                //Read and load rest of file
                try {
                    int counter = 0;
                    JanusGraphTransaction transaction = graph.newTransaction();
                    Function<String,Object> parser = Parsers.getParser(s.getVPropertyClass(vLabel, header[1]));
                    while ((line = br.readLine()) != null) {
                        if(counter%1000 == 0) {
                            logger.info("Loading property "+counter);
                        }
                        String[] row = line.split(CSVSPLIT);
                        Long vertexId = Long.parseLong(row[0]);
                        String janusgraphKey = vLabel+"."+header[1];
                        Vertex vertex =  transaction.traversal().V().has(header[0],vertexId).next();
                        if (vertex == null) {
                            logger.error("Vertex property update failed, since no vertex with id {} from line {}",row[0], line );
                            throw new RuntimeException("Vertex "+vertexId+" does not exists");
                        }
                        //This is safe since the header has been validated against the property map
                        vertex.property(janusgraphKey, parser.apply(row[1]));
                        counter++;
                    }
                    transaction.commit();
                } catch (Exception e) {
                    System.err.println("Failed to add properties in " + entry.getKey());
                    e.printStackTrace();
                    graph.close();
                } finally {
                    br.close();
                }
            }
        }
        logger.info("completed load VP");
    }


    private void validateVPHeader(WorkLoadSchema s, String vLabel, String[] header) throws SchemaViolationException {
        Set<String> props = s.getVertexProperties().get(vLabel);
        if (props == null)
            throw new SchemaViolationException("No properties found for the vertex label " + vLabel);

        if (!header[0].equals(vLabel+".id") || !props.contains(header[1])) {
            throw new SchemaViolationException("Unknown property for vertex Type" + vLabel
                    + ", found " + header[1] + " expected " + props);
        }
    }

    private void validateEHeader(WorkLoadSchema s, String eTriple, String[] header)
            throws SchemaViolationException, IllegalArgumentException {
        String[] triple = eTriple.split(TUPLESPLIT);
        if (triple.length != 3)
            throw new IllegalArgumentException("Expected parameter eTriple to " +
                    "contain a string with two '.' delimiters, found" + eTriple);
        String vF = triple[0];
        String eLabel = triple[1];
        String vT = triple[2];

        Set<String> vTypes = s.getVertexTypes().keySet();
        if (!vTypes.contains(vF) || !vTypes.contains(vT))
            throw new SchemaViolationException("Vertex types not found for triple" + eTriple + ", found " + vTypes);

        Set<String> eTypes = s.getEdgeTypes();
        if (!eTypes.contains(eLabel))
            throw new SchemaViolationException("Edge type not found for triple" + eTriple + ", found " + eTypes);

        //This may be null and that's fine, not all edges have properties
        Set<String> props = s.getEdgeProperties().get(eLabel);

        if (!header[0].equals(vF + ".id"))
            throw new SchemaViolationException("First column is not labeled " + vF + ".id, but:" + header[0]);

        if (!header[1].equals(vT + ".id"))
            throw new SchemaViolationException("Second column is not labeled " + vT + ".id, but:" + header[0]);

        for (String col : header) {
            if (col.contains(".id"))
                continue;

            if (props == null || !props.contains(col))
                throw new SchemaViolationException("Unknown property, found " + col + "expected" + props);
        }
    }
    */
}
