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
 * Created on 2013-02-18
 *
 */
package org.biojava.nbio.structure.align.symm.census3.run;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.biojava.nbio.structure.align.util.AtomCache;
import org.biojava.nbio.structure.scop.Astral;
import org.biojava.nbio.structure.scop.Astral.AstralSet;
import org.biojava.nbio.structure.scop.ScopDomain;
import org.biojava.nbio.structure.scop.ScopFactory;
import org.biojava.nbio.structure.align.symm.census3.representatives.ScopSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A census that takes a list of sun Ids and a sequence clustering. Uses sequence clustering provided by ASTRAL.
 * 
 * @author dmyersturnbull
 */
public class AstralScopDescriptionCensus extends ScopDescriptionCensus {

	private final static Logger logger = LoggerFactory.getLogger(AstralScopDescriptionCensus.class);

	public static void buildDefault(File censusFile, AstralSet astral, int[] sunIds) {
		try {
			int maxThreads = Runtime.getRuntime().availableProcessors() - 1;
			AstralScopDescriptionCensus census = new AstralScopDescriptionCensus(maxThreads, astral, sunIds);
			census.setOutputWriter(censusFile);
			AtomCache cache = new AtomCache();
			cache.setFetchFileEvenIfObsolete(true);
			census.setCache(cache);
			census.run();
			System.out.println(census);
		} catch (RuntimeException e) {
			logger.error(e.getMessage(), e);
		}
	}

	public static void main(String[] args) {
		if (args.length < 3) {
			System.err.println("Usage: " + AstralScopDescriptionCensus.class.getSimpleName() + " output-census-file astral-version-id sun-id-1 [sun-id-2 sun-id-3 ...]");
			return;
		}
		ScopFactory.setScopDatabase(ScopFactory.getSCOP());
		final File censusFile = new File(args[0]);
		final AstralSet astral = AstralSet.parse(args[1]);
		int[] sunIds = new int[args.length - 2];
		StringBuilder sb = new StringBuilder();
		for (int i = 2; i < args.length; i++) {
			Integer sunId = ScopSupport.getInstance().getSunId(args[i]);
			if (sunId == null) throw new IllegalArgumentException("Couldn't find " + args[i]);
			sb.append(sunId + ",");
			sunIds[i - 2] = sunId;
		}
		logger.info("Running on " + sb.toString().substring(0, sb.toString().length()-1));
		buildDefault(censusFile, astral, sunIds);
	}

	private AstralSet astral;
	
	public AstralScopDescriptionCensus(int maxThreads, AstralSet astral, int[] sunIds) {
		super(maxThreads, sunIds);
		this.astral = astral;
	}

	@Override
	protected List<ScopDomain> getDomains() {

		final Set<String> clusterRepresentatives = Astral.getRepresentatives(astral);
		List<ScopDomain> domains = new ArrayList<ScopDomain>();

		if (sunIds == null) throw new RuntimeException(super.getClass().getSimpleName() + " didn't set the sun Ids");

		for (int sunId : sunIds) {
			
			List<ScopDomain> putative = new ArrayList<ScopDomain>();
			ScopSupport.getInstance().getAllDomainsUnder(sunId, putative, false);

			for (ScopDomain domain : putative) {
				if (clusterRepresentatives.contains(domain.getScopId())) domains.add(domain);
			}

		}

		return domains;
	}

}
