/*
 *                    BioJava development code
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  If you do not have a copy,
 * see:
 *
 *      http://www.gnu.org/copyleft/lesser.html
 *
 * Copyright for this code is held jointly by the individual
 * authors.  These should be listed in @author doc comments.
 *
 * For more information on the BioJava project and its aims,
 * or to join the biojava-l mailing list, visit the home page
 * at:
 *
 *      http://www.biojava.org/
 *
 * Created on 2012-11-20
 *
 */

package org.biojava.nbio.structure.align.symm.protodomain;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;

import org.biojava.nbio.structure.Atom;
import org.biojava.nbio.structure.AtomPositionMap;
import org.biojava.nbio.structure.Group;
import org.biojava.nbio.structure.ResidueNumber;
import org.biojava.nbio.structure.ResidueRangeAndLength;
import org.biojava.nbio.structure.ResidueRangeAndLength;
import org.biojava.nbio.structure.Structure;
import org.biojava.nbio.structure.StructureException;
import org.biojava.nbio.structure.StructureTools;
import org.biojava.nbio.structure.align.client.StructureName;
import org.biojava.nbio.structure.align.model.AFPChain;
import org.biojava.nbio.structure.align.util.AtomCache;
import org.biojava.nbio.structure.io.util.FileDownloadUtils;
import org.biojava.nbio.structure.scop.ScopDomain;
import org.biojava.nbio.structure.scop.ScopFactory;

/**
 * A symmetry subunit for some {@link ScopDomain SCOP domain}. Optionally contains a {@link Structure} as
 * {@link #getStructure() structure}. Responsible for finding the symmetry subunit of a Structure given an appropriately
 * formatted string or an {@link AFPChain} and {@link Atom} array, and for determining a unique name for this
 * Protodomain. This class is useful for insisting that a protodomain created from an alignment remains within the
 * confines of its domain. A Protodomain created from an AFPChain is guaranteed to have this property (but not one
 * created from a String). To get the protodomain structure string using CE-Symm, do:
 * <pre>
 *  AFPChain afpChain = ceSymm.align(ca1, ca2);
 *  int order = CeSymm.getSymmetryOrder(afpChain)
 *  String s = Protodomain.fromSymmetryAlignment(afpChain, ca1, order, atomCache).toString();
 * </pre>
 * 
 * <p>To get the protdomain structure string by using CE to align a structure against a protodomain of known
 * symmetry (and symmetry order), do:
 * <pre>
 *  AFPChain afpChain = ceSymm.align(ca1, ca2);
 *  String s = Protodomain.fromReferral(afpChain, ca2, order, atomCache).toString(); // note that we pass in ca2
 * </pre>
 * 
 * @author dmyerstu
 */
public class Protodomain {

	/**
	 * A helper class to create {@link ResidueRangeAndLength ResidueRanges} without making a mistake.
	 * 
	 * @author dmyerstu
	 */
	private static class RangeBuilder {
		private String currentChain;
		private ResidueNumber currentStart;
		private List<ResidueRangeAndLength> list = new ArrayList<ResidueRangeAndLength>();

		void addChain(String chainId) {
			if (currentChain != null) throw new IllegalStateException();
			currentChain = chainId;
		}

		void addEnd(ResidueNumber end, int length) {
			if (currentStart == null) throw new IllegalStateException();
			list.add(new ResidueRangeAndLength(currentChain, currentStart, end, length));
			clearCurrent();
		}

		void addStart(ResidueNumber start) {
			if (currentChain == null) throw new IllegalStateException();
			if (currentStart != null) throw new IllegalStateException();
			currentStart = start;
		}

		void clearCurrent() {
			currentChain = null;
			currentStart = null;
		}

		List<ResidueRangeAndLength> getList() {
			return list;
		}
	}

	/**
	 * If two {@link ResidueRangeAndLength ResidueRanges} consecutive in the {@link #getRanges() list of ranges} differ by no
	 * more than this number, they will be spliced together.
	 */
	private static final int APPROX_CONSECUTIVE = 4;

	private AtomCache cache;

	private int length;

	private final List<ResidueRangeAndLength> list;

	private final String pdbId;

	private final String ranges;

	private final String enclosingName;

	private Structure structure;

	/**
	 * Client code should avoid calling this method if either fromSymmetryAlignment or fromReferral is more appropriate.
	 * The created Protodomain will include a gap in the alignment if and only if it is no greater than {@link #APPROX_CONSECUTIVE} residues.
	 * That is, the Protodomain will have two {@link ResidueRangeAndLength ResidueRanges} for every gap that contains {@code APPROX_CONSECUTIVE} residues.
	 * Currently, {@code APPROX_CONSECUTIVE} is 4; a subsequent change will break the interface.
	 * 
	 * @param enclosingName
	 *            The name of the enclosing structure; can be a SCOP domain, a PDB Id, or a String of the form {@code PDB_ID:CHAIN_ID}
	 * @param afpChain
	 *            The AFPChain returned by the alignment. {@code afpChain.getName1()} and {@code afpChain.getName2()}
	 *            must be non-null and correct.
	 * @param ca
	 *            An array of C-alpha {@link Atom Atoms} from which to create the Protodomain
	 * @param keepStructure
	 *            If set to true, the structure will be recreated.
	 * @param numBlocks
	 *            The number of blocks in the alignment to include. Should either be 1 or 2.
	 * @param chainIndex
	 *            The chain in {@code afpChain} to create the Protodomain from.
	 * @return
	 * @throws ProtodomainCreationException
	 */
	public static Protodomain fromAfpChain(String enclosingName, AFPChain afpChain, Atom[] ca,
			int numBlocks, int chainIndex, AtomCache cache) throws ProtodomainCreationException {
		StructureName name = new StructureName(enclosingName);
		
		//SubstructureIdentifier sid = mew SubstructureIdentifier(enclosingName);
		
		List<String> domainRanges;
		//Check if its a file instead
		File file = new File(FileDownloadUtils.expandUserHome(enclosingName));
		if (file.exists()) {
			return null;
		}
		if (name.isScopName()) {
			// SCOP
			domainRanges = ScopFactory.getSCOP().getDomainByScopID(enclosingName).getRanges();
		} else if (enclosingName.contains(".")) {
			// chain
			domainRanges = new ArrayList<String>();
			domainRanges.add(name.getChainId() + ":");
		} else if ( name.isPdbId()){
			// whole PDB Id
			domainRanges = new ArrayList<String>();
			Set<String> chains = new HashSet<String>();
			for (Atom atom : ca) {
				chains.add(atom.getGroup().getChainId());
			}
			for (String chain : chains) domainRanges.add(chain + ":");
//		} else if (  name.hasRanges()){
//			domainRanges = name.getRanges();
		} else {
			throw new ProtodomainCreationException(enclosingName, "","Don't know how to interpret " + enclosingName + " !");
		}
		return fromAfpChain(name.getPdbId().toLowerCase(), enclosingName, domainRanges, afpChain, ca, numBlocks, chainIndex, cache);
		
	}


	/**
	 * Client code should avoid calling this method if either fromSymmetryAlignment or fromReferral is more appropriate.
	 * The created Protodomain will include a gap in the alignment if and only if it is no greater than {@link #APPROX_CONSECUTIVE} residues.
	 * That is, the Protodomain will have two {@link ResidueRangeAndLength ResidueRanges} for every gap that contains {@code APPROX_CONSECUTIVE} residues.
	 * Currently, {@code APPROX_CONSECUTIVE} is 4; a subsequent change will break the interface.
	 * 
	 * @param enclosingName
	 *            The name of the enclosing structure; can be a SCOP domain, a PDB Id, or a String of the form {@code PDB_ID:CHAIN_ID}
	 * @param domainRanges
	 *            A list of Strings of the form {@code chain:} or {@code chain:start-end}
	 * @param afpChain
	 *            The AFPChain returned by the alignment. {@code afpChain.getName1()} and {@code afpChain.getName2()}
	 *            must be non-null and correct.
	 * @param ca
	 *            An array of C-alpha {@link Atom Atoms} from which to create the Protodomain
	 * @param keepStructure
	 *            If set to true, the structure will be recreated.
	 * @param numBlocks
	 *            The number of blocks in the alignment to include. Should either be 1 or 2.
	 * @param chainIndex
	 *            The chain in {@code afpChain} to create the Protodomain from.
	 * @return
	 * @throws ProtodomainCreationException
	 */
	public static Protodomain fromAfpChain(String pdbId, String enclosingName, List<String> domainRanges, AFPChain afpChain, Atom[] ca,
			int numBlocks, int chainIndex, AtomCache cache) throws ProtodomainCreationException {

		// int numAtomsInBlock1Alignment = afpChain.getOptLen()[0];
		// if (numAtomsInBlock1Alignment == 0) throw new ProtodomainCreationException("unknown", scopId);

		// this keeps a list of insertion-code-independent positions of the groups (where they are in the PDB file)
		final AtomPositionMap map = getAminoAcidPositions(cache, pdbId, enclosingName);

		// since CE-Symm has two blocks, we're going to have to do this in 2 parts:
		// from the start of block 1 to end end of block 1
		// PLUS from the start of block 2 to the end of block 2


		// we rely on the fact that SCOP won't give us a range that contains multiple chains
		// instead, any residues from another chain will be in a different range
		// however, CE-Symm CAN give us single blocks containing residues from different chains
		// we can deal with this by splitting each block into singleton sequential residues (e.g. A_7-7)
		// then we can splice these back together as we would with blocks

		final List<ResidueRangeAndLength> totalRanges = new ArrayList<ResidueRangeAndLength>();

		for (int block = 0; block < numBlocks; block++) {
			if (block + 1 > afpChain.getOptLen().length) {
				System.out.println("Warning: a block is missing in AFPChain from the alignment of " + enclosingName
						+ " Assuming the alignment ends in block " + (block + 1) + ".");
				break;
			}
			final int numCaInEntireBlock = afpChain.getOptLen()[block];
			final int[] alignedPositions = afpChain.getOptAln()[block][chainIndex];
			// final int[] alignedPositions = afpChain.getOptAln()[block][chainIndex];
			for (int i = 0; i < numCaInEntireBlock; i++) {
				final int blockStart = alignedPositions[i];
				final int blockEnd = alignedPositions[i];
				final Group startGroup = ca[blockStart].getGroup();
				final Group endGroup = ca[blockEnd].getGroup();
				final ResidueNumber protodomainStartR = startGroup.getResidueNumber();
				final ResidueNumber protodomainEndR = endGroup.getResidueNumber();
				final int protodomainStart = map.getPosition(protodomainStartR);
				final int protodomainEnd = map.getPosition(protodomainEndR);
				final List<ResidueRangeAndLength> blockRanges = getBlockRanges(protodomainStart, protodomainEnd, domainRanges,
						protodomainStartR, protodomainEndR, startGroup, endGroup, map, enclosingName);
				if (blockRanges != null) { // should this always be true?
					totalRanges.addAll(blockRanges);
				}
			}
		}

		final List<ResidueRangeAndLength> splicedRanges;
		if (totalRanges.isEmpty()) {
			splicedRanges = totalRanges;
		} else {
			splicedRanges = spliceApproxConsecutive(map, totalRanges, APPROX_CONSECUTIVE);
		}

		final int length = ResidueRangeAndLength.calcLength(splicedRanges);

		Protodomain protodomain = new Protodomain(pdbId, enclosingName, splicedRanges, length, cache);

		protodomain.length = length; // is the length of the AFPChain
		return protodomain;

	}

	/**
	 * Creates a Protodomain from an alignment between a Protodomain of known symmetry and some other structure.
	 * 
	 * @param afpChain
	 *            The <em>referred</em> structure (result) must be in position 2, and the <em>referring</em> structure
	 *            (query) in position 1.
	 * @param ca
	 *            An array of Atoms of the result
	 * @param cache
	 * @return
	 * @throws ProtodomainCreationException
	 */
	public static Protodomain fromReferral(AFPChain afpChain, Atom[] ca, AtomCache cache)
			throws ProtodomainCreationException {
		if (afpChain.getOptLen().length != 1) throw new ProtodomainCreationException("unknown", afpChain.getName2(),
				"The AFPChain did not contain exactly 1 block.");
		try {
			return fromAfpChain(afpChain.getName2(), afpChain, ca, 1, 1, cache);
		} catch (Exception e) { // IOException | StructureException | ArrayIndexOutOfBoundsException |
			// NullPointerException
			throw new ProtodomainCreationException("unknown", afpChain.getName2(), e);
		}
	}

	/**
	 * Creates a new Protodomain from a String. Ranges for this Protodomain will <em>not</em> be spliced;
	 * if this is desired, call {@link #spliceApproxConsecutive()} or {@link #spliceApproxConsecutive(int)}.
	 * 
	 * @param string
	 *            A string of the form: pdbId.chain_start-end,chain_start-end, ..., chain_start-end.
	 * @param scopDomain
	 * @return
	 * @throws ProtodomainCreationException
	 */
	public static Protodomain fromString(String string, ScopDomain scopDomain,
			AtomCache cache) throws ProtodomainCreationException {
		return fromString(string, scopDomain.getScopId(), cache);
	}

	/**
	 * Creates a new Protodomain from a String. Ranges for this Protodomain will <em>not</em> be spliced;
	 * if this is desired, call {@link #spliceApproxConsecutive()} or {@link #spliceApproxConsecutive(int)}.
	 * 
	 * @param string
	 *            A string of the form: pdbId.chain_start-end,chain_start-end, ..., chain_start-end.
	 * @param enclosingName
	 * @return
	 * @throws ProtodomainCreationException
	 */
	public static Protodomain fromString(String string, String enclosingName, AtomCache cache)
			throws ProtodomainCreationException, IllegalArgumentException {

		String pdbId = new StructureName(enclosingName).getPdbId().toLowerCase();

		final AtomPositionMap map = Protodomain.getAminoAcidPositions(cache, pdbId, enclosingName);
		final List<ResidueRangeAndLength> list = ResidueRangeAndLength.parseMultiple(string.substring(string.indexOf('.') + 1), map);

		int length = ResidueRangeAndLength.calcLength(list);

		Protodomain protodomain = new Protodomain(pdbId, enclosingName, list, length, cache);
		return protodomain;
	}

	/**
	 * Creates a Protodomain from an AFPChain returned by CE-Symm.
	 * 
	 * @param afpChain
	 * @param ca
	 *            An array of Atoms of the result
	 * @param cut
	 *            What the whole protodomain will be "cut" by. This should ordinarily be set to the symmetry order. If
	 *            it is set to 1, it the whole protodomain will be returned.
	 * @param cache
	 * @return
	 * @throws ProtodomainCreationException
	 */
	public static Protodomain fromSymmetryAlignment(AFPChain afpChain, Atom[] ca, int order, AtomCache cache)
			throws ProtodomainCreationException {
		// note that order isn't required in the current implementation, but it could be
		if (afpChain.getBlockNum() != 2) throw new ProtodomainCreationException("unknown", afpChain.getName2(),
				"The AFPChain did not contain exactly 2 blocks.");
		return fromAfpChain(afpChain.getName1(), afpChain, ca, 2, 0, cache);
	}

	public static Protodomain append(AtomPositionMap map, AtomCache cache, Protodomain... protodomains) {
		if (protodomains.length == 0) return null;
		if (protodomains.length == 1) return new Protodomain(protodomains[0]);
		String scopId = protodomains[0].getScopId();
		List<ResidueRangeAndLength> ranges = new ArrayList<ResidueRangeAndLength>();
		int previousEnd = 0;
		for (Protodomain p : protodomains) {
			if (!p.getScopId().equalsIgnoreCase(scopId)) {
				throw new IllegalArgumentException("Protodomains do not belong to the same enclosing structure");
			}
			int start = map.getPosition(p.getRanges().get(p.getRanges().size()-1).getStart());
			if (start < previousEnd) {
				throw new IllegalArgumentException("Protodomains must be in order");
			}
			previousEnd = map.getPosition(p.getRanges().get(p.getRanges().size()-1).getEnd());
			ranges.addAll(p.getRanges());
		}
		int length = ResidueRangeAndLength.calcLength(ranges);
		Protodomain protodomain = new Protodomain(scopId, protodomains[0].getPdbId(), ranges, length, cache);
		return protodomain;		
	}
	
	/**
	 * From a list of residue ranges {@code residueRanges} and a number of residues wanted {@code numResiduesWant} (
	 * <em>which includes any alignment gaps</em>), returns a new list of residue ranges that contains the first
	 * {@code numResiduesWant} residues from {@code residueRanges}.
	 * 
	 * @param residueRanges
	 * @param groupPositions
	 *            A map returned from {@link ResidueRangeAndLength#getAminoAcidPositions(Atom[])}.
	 * @param navMap
	 *            An ordered map returned from {@link ResidueRangeAndLength#orderByAtoms(Map)}.
	 * @param index
	 *           TODO Write a comment
	 * @return
	 */
	private static List<ResidueRangeAndLength> calcSubstruct(List<ResidueRangeAndLength> residueRanges, AtomPositionMap map, int order,
			int index) {

		if (order < 1) throw new IllegalArgumentException("Can't compute substructure if order is " + order);
		if (index > order) throw new IndexOutOfBoundsException("Illegal index "+index+" out of "+order+" rotations");
		
		List<ResidueRangeAndLength> part = new ArrayList<ResidueRangeAndLength>(); // the parts we want to keep

		int numResiduesHave = 0;
		int numResiduesSkipped = 0;
		final int totalLen = ResidueRangeAndLength.calcLength(residueRanges);

		// Number of residues to skip before the requested block
		final int numResiduesWantToSkip = Math.round((float)totalLen * index / order);
		// Size of requested block
		final int numResiduesWant = Math.round((float)totalLen * (index+1)/ order) - numResiduesWantToSkip;

		final NavigableMap<ResidueNumber, Integer> navMap = map.getNavMap();

		outer: for (ResidueRangeAndLength rr : residueRanges) {

			// Skip residues
			if (numResiduesSkipped + rr.getLength() <= numResiduesWantToSkip) {
				numResiduesSkipped += rr.getLength();
				continue; // skip all of it
			} else if (numResiduesSkipped <= numResiduesWantToSkip) {
				// we only want part
				// we'll execute the block below
				// but before doing this, we'll redefine rr to remove the skipped residues
				ResidueNumber redefStart = rr.getStart();
				for (int j = 0; j < numResiduesWantToSkip - numResiduesSkipped; j++) {
					redefStart = navMap.higherEntry(redefStart).getKey();
				}
				rr = new ResidueRangeAndLength(rr.getChainId(), redefStart, rr.getEnd(), rr.getLength() - (numResiduesWantToSkip - numResiduesSkipped));
				numResiduesSkipped = numResiduesWantToSkip;
			}
			
			// Add residues
			// note that getLength() here DOES INCLUDE gaps (and it should)
			if (numResiduesHave + rr.getLength() <= numResiduesWant) {
				// no problem: we can fit the entire residue range in
				part.add(rr);
				numResiduesHave += rr.getLength();
			} else if (numResiduesHave <= numResiduesWant) {
				// okay, so we can only fit some of rr in (and then we'll end)
				// we'll insert rr from rr.getStart() to rr.getStart() + numResiduesWant - numResiduesHave
				// unfortunately, this is harder because of insertion codes
				ResidueNumber endResidueNumber = rr.getStart();
				// okay, we got to the end of rr
				// now we need to go forward by numResiduesWant - numResiduesTraversed
				for (numResiduesHave++; numResiduesHave < numResiduesWant; numResiduesHave++) {
					endResidueNumber = navMap.higherEntry(endResidueNumber).getKey();
				}
				part.add(new ResidueRangeAndLength(rr.getChainId(),
						rr.getStart(), endResidueNumber,
						numResiduesWant - numResiduesHave));
				break outer; // we filled up numResiduesTraversed, so we won't be able to add further residue ranges

			}
		}
		return part;
	}

	/**
	 * Really just calls {@link ResidueRangeAndLength#getAminoAcidPositions(Atom[])}.
	 * 
	 * @param cache
	 * @param scopDomain
	 * @return
	 * @throws ProtodomainCreationException
	 */
	private static AtomPositionMap getAminoAcidPositions(AtomCache cache, String pdbId, String enclosingName)
			throws ProtodomainCreationException {
		try {
			// We cannot use CA atoms only here because sometimes the C-alpha atom is missing
			// Our AtomPositionMap should use something more liberal (see the AtomPositionMap constructor)
			Structure structure = StructureTools.getStructure(pdbId, null, cache);
			final Atom[] allAtoms = StructureTools.getAllAtomArray(structure); // TODO is using scopId
			// ok here?
			return new AtomPositionMap(allAtoms);
		} catch (IOException e) {
			throw new ProtodomainCreationException("unknown", enclosingName, e,
					"Could not get a list of amino acid residue number positions.");
		} catch (StructureException e) {
			throw new ProtodomainCreationException("unknown", enclosingName, e,
					"Could not get a list of amino acid residue number positions.");
		}
	}

	/**
	 * Finds the list of residue ranges that corresponds to the protodomain between {@code protodomainStartR} and
	 * {@code protodomainEndR} <em>within the {@link ScopDomain} identified by {@code domainRanges}</em>.
	 * 
	 * @param protodomainStart
	 *            The Protodomain's ATOM record start position
	 * @param protodomainEnd
	 *            The Protodomain's ATOM record end position
	 * @param domainRanges
	 *            A list of ranges returned by calling {@link ScopDomain#getRanges()} on this Protodomain's
	 *            {@link ScopDomain}.
	 * @param protodomainStartR
	 *            The Protodomain's ResidueNumber start position
	 * @param protodomainEndR
	 *            The Protodomain's ResidueNumber end position
	 * @param blockStartGroup
	 *            The {@link Group} at the start of a block in the the alignment
	 * @param blockEndGroup
	 *            The {@link Group} at the end of a block in the the alignment
	 * @param posns
	 *            A HashMap from {@link ResidueRangeAndLength#getAminoAcidPositions(Atom[])}.
	 * @param navMap
	 *            A NavigableMap from {@link ResidueRangeAndLength#orderByAtoms(Map)}.
	 * @return A list of {@link ResidueRangeAndLength ResidueRanges} within the overlap (intersection) of the relevant block of
	 *         the alignment corresponding to this Protodomain, and its ScopDomain's boundaries.
	 * @throws ProtodomainCreationException
	 */
	private static List<ResidueRangeAndLength> getBlockRanges(int protodomainStart, int protodomainEnd,
			List<String> domainRanges, ResidueNumber protodomainStartR, ResidueNumber protodomainEndR,
			Group blockStartGroup, Group blockEndGroup, AtomPositionMap map, String scopId)
					throws ProtodomainCreationException {

		/*
		 * Okay, things get annoying here, since we need to handle:
		 * 1. domain insertions, AND
		 * 2. heteroatom Groups inside this domain that are past the last amino acid
		 * If we simply take the protodomain's start and its end (in this block), we could include:
		 * 1. domains inserted between, AND
		 * 2. residues in other domains between the last amino acid in the correct domain and the last heteroatom (especially a water molecule) in the correct domain
		 * These are bad. So: we use SCOP's list of ranges for our domain, making sure that our protodomain does not extend outside
		 * its boundaries.
		 * To handle #2, we -could- get the last amino acid (for the end residue; the first aa for the start), but this is problematic for modified amino acids. We don't do this.
		 * Some heteroatoms also have alpha carbons.
		 * But that's not all. What about multi-chain domains? We need to handle those.
		 * Then things can get even MORE cumbersome thanks to insertion codes. d1qdmc1
		 * has an insertion code in one of its SCOP ranges: C:1S-104S (Residue numbers in ATOMs go: 247 1S 2S ... 103S 104S 248)
		 */

		if (protodomainStart > protodomainEnd) return null; // it's okay if our protodomain doesn't exist in this block

		RangeBuilder rangeBuilder = new RangeBuilder();
		for (String domainRange : domainRanges) {

			// a range is of the form: A:05-117 (chain:start-end) OR just A:
			ResidueRangeAndLength rr = ResidueRangeAndLength.parse(domainRange, map);
			ResidueNumber domainStartR = rr.getStart();
			ResidueNumber domainEndR = rr.getEnd();
			int domainStart, domainEnd;

			// these are the insertion-code-independent positions
			Integer domainStartO = map.getPosition(domainStartR);
			if (domainStartO == null) throw new ProtodomainCreationException("unknown", scopId,
					"Couldn't find the start of the SCOP domain in the PDB file, which was supposed to be "
							+ domainStartR.printFull());
			domainStart = domainStartO;
			Integer domainEndO = map.getPosition(domainEndR);
			if (domainEndO == null) throw new ProtodomainCreationException("unknown", scopId,
					"Couldn't find the end of the SCOP domain in the PDB file, which was supposed to be "
							+ domainEndR.printFull());
			domainEnd = domainEndO;

			if (domainStart > domainEnd) return null; // in this case, there's something wrong with the SCOP definition

			final String chain = domainRange.substring(0, 1);
			rangeBuilder.addChain(chain);

			if (domainEnd < protodomainStart) { // the domain part starts and ends before the start of the protodomain
				rangeBuilder.clearCurrent();
				continue; // well, obviously we're not using that part of the domain
			} else if (domainStart <= protodomainStart) { // the domain part starts before the protodomain starts but
				// ends after the protodomain starts
				rangeBuilder.addStart(protodomainStartR); // protodomain start
				if (domainEnd <= protodomainEnd) { // the domain part ends before the protodomain ends
					rangeBuilder.addEnd(domainEndR, map.getLength(domainEnd, protodomainStart, chain)); // domain
					// end
				} else { // the domain part ends after the protodomain ends
					rangeBuilder.addEnd(protodomainEndR, map.getLength(protodomainEnd, protodomainStart, chain)); // protodomain
					// end
				}
			} else if (domainStart > protodomainEnd) { // domain part ends before the protodomain starts
				rangeBuilder.clearCurrent();
				continue; // if we knew the domain parts are ordered, we could break
			} else if (domainStart > protodomainStart) { // the domain part starts after the protodomain starts but ends
				// after it starts
				rangeBuilder.addStart(domainStartR); // domain start
				if (domainEnd <= protodomainEnd) {
					rangeBuilder.addEnd(domainEndR, map.getLength(domainEnd, domainStart, chain)); // domain
					// end
				} else {
					rangeBuilder.addEnd(protodomainEndR, map.getLength(protodomainEnd, domainStart, chain)); // protodomain
					// end
				}
			} else { // this can't happen
				throw new RuntimeException(); // might as well catch a bug immediately
			}
		}

		return rangeBuilder.getList();
	}

	/**
	 * @see #spliceApproxConsecutive(int)
	 */
	private static List<ResidueRangeAndLength> spliceApproxConsecutive(AtomPositionMap map, List<ResidueRangeAndLength> old,
			int approxConsecutive) {
		List<ResidueRangeAndLength> spliced = new ArrayList<ResidueRangeAndLength>(old.size());

		ResidueRangeAndLength growing = old.get(0);

		for (int i = 1; i < old.size(); i++) {

			final ResidueRangeAndLength next = old.get(i);

			if(growing.getChainId().equals(next.getChainId())) {
				final int dist = map.getLengthDirectional(growing.getEnd(), next.getStart())-2;
				
				if (dist >= approxConsecutive) {
					// Start a new residue range
					spliced.add(growing);
					growing = next;
				} else {
					// Extend growing range
					growing = new ResidueRangeAndLength(growing.getChainId(), growing.getStart(), next.getEnd(), growing.getLength()
							+ next.getLength() + dist);
				}
			} else {
				// Different chains force new range
				spliced.add(growing);
				growing = next;
			}

		}

		spliced.add(growing);

		return spliced;
	}

	/**
	 * @param pdbId
	 * @param enclosingName
	 * @param list
	 *            A list of {@link ResidueRangeAndLength ResidueRanges} that define this Protodomain.
	 * @param length
	 *            The number of amino acids contained in this Protodomain, <em>including any alignment gaps</em>. If
	 *            each ResidueRangeAndLength in {@code list} has a non-null {@link ResidueRangeAndLength#getLength() length}, the sum of
	 *            these lengths should be equal to this argument.
	 * @param cache
	 */
	public Protodomain(String pdbId, String enclosingName, List<ResidueRangeAndLength> list, int length, AtomCache cache) {
		this.pdbId = pdbId;
		this.enclosingName = enclosingName;
		this.list = list;
		this.cache = cache;
		this.length = length;
		StringBuilder sb = new StringBuilder();
		Iterator<ResidueRangeAndLength> iter = list.iterator();
		while (iter.hasNext()) {
			ResidueRangeAndLength t = iter.next();
			sb.append(t);
			if (iter.hasNext()) sb.append(",");
		}
		ranges = sb.toString();
	}

	public Protodomain(Protodomain protodomain) {
		this.cache = protodomain.cache;
		this.enclosingName = protodomain.enclosingName;
		this.length = protodomain.length;
		this.list = protodomain.list;
		this.pdbId = protodomain.pdbId;
		this.structure = protodomain.structure;
		this.ranges = protodomain.ranges;
	}


	/**
	 * Builds the structure of this Protodomain so that it can be returned by {@link #getStructure()}.
	 * 
	 * @throws IOException
	 * @throws StructureException
	 * @see #getStructure()
	 * @return The structure
	 */
	public Structure buildStructure() throws IOException, StructureException {
		if (structure == null) {
			structure = cache.getStructure(toString());
			structure.setName(toString());
		}
		return structure;
	}

	/**
	 * Creates a new Protodomain corresponding to this Protodomain cut by {@code order}. For example, if this
	 * Protodomain contains 6 symmetry subunits, calling this.createSubgroup(3) will return the Protodomain
	 * corresponding to the first 2 symmetry subunits.
	 * 
	 * @param order
	 * @return
	 * @throws ProtodomainCreationException
	 */
	public Protodomain createSubstruct(int order) throws ProtodomainCreationException {
		return createSubstruct(order, 0);
	}

	/**
	 * Creates a new Protodomain corresponding to this Protodomain cut by {@code order}. For example, if this
	 * Protodomain contains 6 symmetry subunits, calling this.createSubgroup(3) will return the Protodomain
	 * corresponding to the first 2 symmetry subunits.
	 * 
	 * @param order
	 * @param index TODO Write a comment
	 * @return
	 * @throws ProtodomainCreationException
	 */
	public Protodomain createSubstruct(int order, int index) throws ProtodomainCreationException {
		AtomPositionMap map = Protodomain.getAminoAcidPositions(cache, pdbId, enclosingName);
		List<ResidueRangeAndLength> rrs = calcSubstruct(list, map, order, index);
		return new Protodomain(pdbId, enclosingName, rrs, ResidueRangeAndLength.calcLength(rrs), cache);
	}


	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (obj == null) return false;
		if (getClass() != obj.getClass()) return false;
		Protodomain other = (Protodomain) obj;
		if (pdbId == null) {
			if (other.pdbId != null) return false;
		} else if (!pdbId.equals(other.pdbId)) return false;
		if (ranges == null) {
			if (other.ranges != null) return false;
		} else if (!ranges.equals(other.ranges)) return false;
		return true;
	}

	public AtomCache getCache() {
		return cache;
	}

	/**
	 * @return The number of amino acids in this Protodomain, including any alignment gaps.
	 */
	public int getLength() {
		return length;
	}

	public String getPdbId() {
		return pdbId;
	}

	/**
	 * @return The list of {@link ResidueRangeAndLength ResidueRanges} in this Protodomain.
	 */
	public List<ResidueRangeAndLength> getRanges() {
		return list;
	}

	/**
	 * @return A String describing the list of {@link ResidueRangeAndLength ResidueRanges} of this Protodomain of the format
	 *         chain_start-end,chain.start-end, ... For example, <code>A_5-100,110-144</code>.
	 */
	public String getRangeString() {
		return ranges;
	}

	/**
	 * @return This Protodomain's parent {@link ScopDomain#getScopId() SCOP id}.
	 */
	public String getScopId() {
		return enclosingName;
	}

	/**
	 * Same as {@link #toString()}.
	 * 
	 * @return
	 */
	public String getString() {
		return toString();
	}

	/**
	 * @return The Structure of this Protodomain, or null if it has not been created. To create the structure, call
	 *         {@link #buildStructure()}.
	 */
	public Structure getStructure() {
		return structure;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + (pdbId == null ? 0 : pdbId.hashCode());
		result = prime * result + (ranges == null ? 0 : ranges.hashCode());
		return result;
	}

	/**
	 * Nullifies the structure of this Protodomain (presumably to free memory).
	 * 
	 * @see #getStructure()
	 */
	public void removeStructure() {
		structure = null;
	}

	/**
	 * Sets the {@link AtomCache} used in this Protodomain. This would only need to be called if
	 * {@link #buildStructure()} will be called, and a different AtomCache is needed.
	 * 
	 * @param cache
	 */
	public void setCache(AtomCache cache) {
		this.cache = cache;
	}

	/**
	 * Splices residues that are approximately consecutive within a distance of {@link #APPROX_CONSECUTIVE}. This is the
	 * same value that is used internally for Protodomains created from AFPChains. Note that Protodomains created from
	 * Strings are never spliced during creation; manually call this method if that is desired.
	 * 
	 * @see #spliceApproxConsecutive(int)
	 */
	public Protodomain spliceApproxConsecutive() {
		return spliceApproxConsecutive(APPROX_CONSECUTIVE);
	}

	/**
	 * Splices residues that are approximately consecutive, within some distance. That is, from a list of
	 * {@link ResidueRangeAndLength ResidueRanges}, returns a new list of ResidueRanges containing every range from {@code old},
	 * but with consecutive ranges starting and ending within {@code approxConsecutive} amino acids spliced together.
	 * Note that ranges from different chains are assigned infinite distance, so they cannot be spliced together. For
	 * example: 1dkl.A_1-100,A_102-200 would be spliced into 1dkl.A_1-200 for {@code approxConsecutive=2} or higher.
	 * 
	 * @param old
	 * @param posns
	 * @param sorted
	 * @param approxConsecutive
	 * @return
	 * @see {@link #APPROX_CONSECUTIVE}.
	 */
	public Protodomain spliceApproxConsecutive(int approxConsecutive) {
		try {
			List<ResidueRangeAndLength> ranges = spliceApproxConsecutive(getAminoAcidPositions(cache, pdbId, enclosingName), list,
					approxConsecutive);
			return new Protodomain(pdbId, enclosingName, ranges, 1, cache);
		} catch (ProtodomainCreationException e) { // this really shouldn't happen, unless the AtomCache has changed
			// since this Protodomain was created
			throw new IllegalArgumentException(e);
		}
	}

	/**
	 * @return A String describing the list of {@link ResidueRangeAndLength ResidueRanges} of this Protodomain of the format
	 *         pdbId.chain_start-end,chain.start-end, ... For example, <code>1w0p.A_5-100,110-144</code>.
	 */
	@Override
	public String toString() {
		return pdbId + "." + ranges;
	}

}
