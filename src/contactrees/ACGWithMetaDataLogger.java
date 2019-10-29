/**
 * 
 */
package contactrees;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import beast.core.BEASTObject;
import beast.core.Input;
import beast.core.Loggable;
import beast.evolution.tree.Node;

/**
 * 
 * 
 * @author Nico Neureiter <nico.neureiter@gmail.com>
 */
public class ACGWithMetaDataLogger extends BEASTObject implements Loggable {


	public Input<ConversionGraph> networkInput = new Input<>(
			"network",
			"The conversion graph to be logged.",
			Input.Validate.REQUIRED);
	public Input<BlockSet> blockSetInput = new Input<>(
			"blockSet",
			"The moves each local tree takes along the conversion graph.",
			Input.Validate.REQUIRED);
	
	protected ConversionGraph acg;
	protected BlockSet blockSet;
	
	@Override
	public void initAndValidate() {
		blockSet = blockSetInput.get();
		acg = networkInput.get();
	}

    @Override
    public void init(PrintStream out) {
    	Node node = acg.getRoot();
        
        out.println("#NEXUS\n");
        out.println("Begin taxa;");
        out.println("\tDimensions ntax=" + acg.getLeafNodeCount() + ";");
        out.println("\t\tTaxlabels");
        acg.printTaxa(node, out, acg.getNodeCount() / 2);
        out.println("\t\t\t;");
        out.println("End;\n");

        out.println("Begin contactrees;");
        out.print("\tblockSet");
        for (Block block: blockSet.blocks)
            out.print(" " + block.getID());			// TODO include affected sites.
        out.println(";\nEnd;\n");

        out.println("Begin trees;");
        out.println("\tTranslate");
        acg.printTranslate(node, out, acg.getNodeCount() / 2);
        out.print(";");
    }

	@Override
	public void close(PrintStream out) {
		acg.close(out);
	}

    /**
     * Obtain extended Newick representation of ACG.  Includes Nexus metadata
     * on hybrid leaf nodes describing the alignment sites affected by the
     * conversion event.
     * 
     * @return Extended Newick string.
     */
    public String getExtendedNewick() {
        return getExtendedNewick(true);
    }

    /**
     * Obtain extended Newick representation of ACG.  Optionally Nexus metadata
     * on hybrid leaf nodes describing the alignment sites affected by the
     * conversion event.
     *
     * @param includeSiteStats if true, include count and ration of affected sites
     * @return Extended Newick string.
     */
    public String getExtendedNewick(boolean includeSiteStats) {
    	Node root = acg.getRoot();
        return extendedNewickTraverse(root, includeSiteStats) + ";";
    }
    
    private String extendedNewickTraverse(Node node,
                                          boolean includeBlockStats) {
    	ConversionList convs = acg.convs;
    	HashMap<Conversion, List<Integer>> affectedBlocks = blockSet.getAffectedBlocks();
    	
        StringBuilder sb = new StringBuilder();
        
        // Determine sequence of events along this node.
        class Event {
            boolean isArrival;
            double time;
            Conversion conv;
            
            public Event(boolean isArrival, double time, Conversion conv) {
                this.isArrival = isArrival;
                this.time = time;
                this.conv = conv;
            }
        }
        List<Event> events = new ArrayList<>();
        for (Conversion conv : convs) {
            if (conv.node1 == node)
                events.add(new Event(false, conv.getHeight(), conv));
            if (conv.node2 == node)
                events.add(new Event(true, conv.getHeight(), conv));
        }

        
        // Sort events from oldest to youngest.
        events.sort((Event e1, Event e2) -> {
            if (e1.time > e2.time) return -1;
            else return 1;
        });

        // Process events.
        
        int cursor = 0;
        
        double lastTime;
        if (node.isRoot())
            lastTime = Double.POSITIVE_INFINITY;
        else
            lastTime = node.getParent().getHeight();

        for (Event event : events) {

            double thisLength;
            if (Double.isInfinite(lastTime))
                thisLength = 0.0;
            else
                thisLength = lastTime - event.time;

            if (event.isArrival) {
                String meta =  String.format(Locale.ENGLISH,
                        "[&conv=%d, relSize=%.2f",
                        event.conv.getID(),
                        affectedBlocks.get(event.conv).size()/(double) blockSet.getBlockCount()
                );
//                String meta =  String.format(Locale.ENGLISH,
//                        "[&conv=%d, region={%d,%d}, locus=\"%s\", relSize=%g",
//                        convs.indexOf(event.conv),
//                        0, 1, 0, 1.);

                if (includeBlockStats) {
                	int affectedBlockCount = blockSet.countAffectedBlocks();
        			double affectedBlockFraction = affectedBlockCount / (double) blockSet.getBlockCount();
        			
//        			affectedBlockFraction = 1.0;
        			
                    meta += String.format(Locale.ENGLISH,
                            ", affectedBlocks=%s",
                            formatList(blockSet.getAffectedBlocks(event.conv))
//                            ", affectedSites=%d, uselessSiteFraction=%g, affectedBlocks=%s",
//                            affectedBlockCount,
//                            1.0-affectedBlockFraction,
//                            formatList(blockSet.getAffectedBlocks(event.conv))
                            );
                }

                if (event.conv.newickMetaDataMiddle != null)
                    meta += ", " + event.conv.newickMetaDataMiddle;

                meta += "]";

                String parentMeta;
                if (event.conv.newickMetaDataTop != null)
                    parentMeta = "[&" + event.conv.newickMetaDataTop + "]";
                else
                    parentMeta = "";

                sb.insert(cursor, "(,#" + event.conv.getID()
                        + meta
                        + ":0.00001" // TODO Fix in IcyTree to avoid this.  
                        + ")"
                        + parentMeta
                        + ":" + thisLength);
                cursor += 1;
            } else {
                String meta;
                if (event.conv.newickMetaDataBottom != null)
                    meta = "[&" + event.conv.newickMetaDataBottom + "]";
                else
                    meta = "";

                sb.insert(cursor, "()#" + event.conv.getID()
                        + meta
                        + ":" + thisLength);
                cursor += 1;
            }
            
            lastTime = event.time;
        }
        
        // Process this node and its children.

        if (!node.isLeaf()) {
            String subtree1 = extendedNewickTraverse(node.getChild(0), includeBlockStats);
            String subtree2 = extendedNewickTraverse(node.getChild(1), includeBlockStats);
            sb.insert(cursor, "(" + subtree1 + "," + subtree2 + ")");
            cursor += subtree1.length() + subtree2.length() + 3;
        }

        double thisLength;
        if (Double.isInfinite(lastTime))
            thisLength = 0.0;
        else
            thisLength = lastTime - node.getHeight();
        sb.insert(cursor, (node.getNr() + acg.taxaTranslationOffset)
                + node.getNewickMetaData() + ":" + thisLength);
        
        return sb.toString();
    }

    @Override
    public void log(long nSample, PrintStream out) {
        out.print(String.format("tree STATE_%d = [&R] %s",
                nSample, getExtendedNewick()));
    }


    /*
     * TESTING INTERFACE
     */
    static public ACGWithMetaDataLogger getACGWMDLogger(ConversionGraph acg, BlockSet blockSet) {
    	ACGWithMetaDataLogger acgwmdLogger = new ACGWithMetaDataLogger();
    	acgwmdLogger.initAndValidate();
    	acgwmdLogger.acg = acg;
    	acgwmdLogger.blockSet = blockSet;
    	return acgwmdLogger;
    }
    
    public ConversionGraph getACG() {
    	return acg;
    }
    
    public String formatList(List lst) {
    	String s = "\"{";
    	int i = 0;
    	for (Object item : lst) {    		
    		s += item;
    		if (i < lst.size()-1)
    			s += ",";
    		i++;
    	}
    	return s + "}\"";
    }
}
