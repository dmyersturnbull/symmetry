package org.biojava3.structure.align.symm.census2.analysis;

import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.biojava.bio.structure.scop.ScopDatabase;
import org.biojava.bio.structure.scop.ScopDescription;
import org.biojava.bio.structure.scop.ScopDomain;
import org.biojava.bio.structure.scop.ScopFactory;
import org.biojava3.structure.align.symm.census2.Result;
import org.biojava3.structure.align.symm.census2.Results;
import org.biojava3.structure.align.symm.census2.stats.Grouping;
import org.biojava3.structure.align.symm.census2.stats.StatUtils;
import org.biojava3.structure.align.symm.census2.stats.order.ErrorKernelDecider;
import org.biojava3.structure.align.symm.census2.stats.order.OrderHelper;

public class NormalizedECs {

	private static final Logger logger = LogManager.getLogger(NormalizedECs.class.getName());

	private Grouping normalizer = Grouping.superfamily();
	private Grouping exampler = Grouping.superfamily();
	private OrderHelper orderHelper;

	public static void main(String[] args) throws IOException {
		if (args.length != 2) {
			System.err.println("Usage: " + ECFinder.class.getSimpleName() + " census-file.xml ecs-file.tsv");
			return;
		}
		Results census = Results.fromXML(new File(args[0]));
		ECFinder finder = ECFinder.fromTabbed(new File(args[1]));
		NormalizedECs ecer = new NormalizedECs();
		Map<String, String> ecs = new HashMap<String, String>();
		ecs.putAll(finder.getEcsBySymmDomain());
		ecs.putAll(finder.getEcsByAsymmDomain());
		ecer.printComparisonSmart(1, 10, census, ecs);
		ecer.printComparisonSmart(2, 5, census, ecs);
	}

	public NormalizedECs() {
		this(Grouping.superfamily(), Grouping.fold(), new OrderHelper(Grouping.superfamily(), 0.4, 0.5, ErrorKernelDecider.fromMatrixFile()));
	}

	public NormalizedECs(Grouping normalizer, Grouping exampler, OrderHelper orderHelper) {
		this.normalizer = normalizer;
		this.exampler = exampler;
		this.orderHelper = orderHelper;
	}

	private class Example {
		private String label;
		
		public Example(String label) {
			this.label = label;
		}
		private Map<String, Set<String>> sfsByFold = new HashMap<String, Set<String>>();
		private Set<String> symmSfsInLabel = new HashSet<String>();
		public void addSymm(String fold, String sf) {
			if (!sfsByFold.containsKey(fold)) {
				sfsByFold.put(fold, new HashSet<String>());
			}
			sfsByFold.get(fold).add(sf);
			symmSfsInLabel.add(sf);
		}
		public Map<String, Integer> getMap() {
			Comparator<String> nSfsInFoldComp = new Comparator<String>() {
				@Override
				public int compare(String fold1, String fold2) {
					if (fold1.equals(fold2)) return 0;
					int fold1Size = sfsByFold.get(fold1).size();
					int fold2Size = sfsByFold.get(fold2).size();
					if (fold1Size > fold2Size) return -1;
					if (fold1Size < fold2Size) return 1;
					return -1;
				}
			};
			SortedMap<String,Integer> sortedFoldExamples = new TreeMap<String,Integer>(nSfsInFoldComp);
			for (Map.Entry<String, Set<String>> entry : sfsByFold.entrySet()) {
				sortedFoldExamples.put(entry.getKey(), entry.getValue().size());
			}
			return sortedFoldExamples;
		}
		public String getAsString(int maxExamples) {
			StringBuilder sb = new StringBuilder();
			Map<String, Integer> examples = getMap();

			sb.append(sfsByFold.size());
			int i = 0;
			for (Map.Entry<String,Integer> example : examples.entrySet()) {
				double fractionOfEc = (double) example.getValue() / symmSfsInLabel.size();
				sb.append("\t" + example.getKey() + "(" + StatUtils.formatP(fractionOfEc) + ")");
				i++;
				if (i > maxExamples) break;
			}

			return sb.toString();
		}
	}

	public void printComparisonSmart(int level, int maxExamples, Results census, Map<String, String> ecsByDomain) {

		ScopDatabase scop = ScopFactory.getSCOP(ScopFactory.VERSION_1_75A);

//		Map<String, Integer> nSymmSfsByLabel = new HashMap<String, Integer>();
//		Map<String, Integer> nTotalSfsByLabel = new TreeMap<String, Integer>();
		Map<String, Set<String>> symmSfsByLabel = new TreeMap<String, Set<String>>();
		Map<String, Set<String>> totalSfsByLabel = new TreeMap<String, Set<String>>();
		Map<String, Example> examples = new HashMap<String, Example>();

		Set<String> symmFolds = getSymmetricFolds(census);
		
		for (Map.Entry<String,String> entry : ecsByDomain.entrySet()) {
			final String scopId = entry.getKey();
			final ScopDomain domain = scop.getDomainByScopID(scopId);
			final String sf = normalizer.group(domain);
			final String label = getLabel(entry.getValue(), level);
			if (label == null) continue; // EC not defined deep enough for our needs
			final boolean isSymm = symmFolds.contains(sf);
			
			// record the superfamily
			if (isSymm) {
				if (!symmSfsByLabel.containsKey(label)) {
					symmSfsByLabel.put(label, new HashSet<String>());
				}
				symmSfsByLabel.get(label).add(sf);
			}
			if (!totalSfsByLabel.containsKey(label)) {
				totalSfsByLabel.put(label, new HashSet<String>());
			}
			totalSfsByLabel.get(label).add(sf);
			
			// record as an example
			final String fold = exampler.group(domain);
			if (!examples.containsKey(label)) {
				examples.put(label, new Example(label));
			}
			if (isSymm) {
				examples.get(label).addSymm(fold, sf);
			}
		}

		for (Map.Entry<String, Set<String>> entry : symmSfsByLabel.entrySet()) {

			final String label = entry.getKey();
			final int nSymm = entry.getValue().size();
			final int nTotal = totalSfsByLabel.containsKey(label)? totalSfsByLabel.get(label).size() : 0;

			System.out.print(label + "\t" + nSymm + "\t" + nTotal);

			/*
			 * now we want to list example domains
			 * for this, we want the top most common folds
			 * so we need a new map sorted by values
			 */
			System.out.println("\t" + examples.get(label).getAsString(maxExamples));
		}
	}

	private Set<String> getSymmetricFolds(Results census) {
		ScopDatabase scop = ScopFactory.getSCOP(ScopFactory.VERSION_1_75A);

		Set<String> folds = new HashSet<String>();

		for (Result result : census.getData()) {
			try {
				ScopDomain domain = scop.getDomainByScopID(result.getScopId());
				if (domain == null) {
					logger.error(result.getScopId() + " is null");
				}
				result.setClassification(domain.getClassificationId());
				orderHelper.add(result);
				String fold = normalizer.group(result);
				folds.add(fold);
			} catch (RuntimeException e) {
				logger.warn("Failed on " + result.getScopId(), e);
			}
		}

		Set<String> symm = new HashSet<String>();
		for (String fold : folds) {
			int order = orderHelper.getConsensusOrder(fold);
			if (order > 1) symm.add(fold);
		}
		return symm;

	}

	/**
	 * Prints a comparison between symmetric and asymmetric results for each EC.
	 * @param level The depth of the EC: 0 for top-level and 3 for 3rd-tier
	 * @param maxExamples The maximum number of example folds to list; example folds are sorted from most prevalent to least
	 */
	@Deprecated
	public void printComparisonOriginal(int level, int maxExamples, Map<String, String> ecsBySymmDomain, Map<String, String> ecsByAsymmDomain) {

		ScopDatabase scop = ScopFactory.getSCOP(ScopFactory.VERSION_1_75A);

		if (level < 1 || level > 4) throw new IllegalArgumentException("Level must be between 1 and 4, inclusive");

		/*
		 * build a map of the number of symmetric domains by EC
		 * also record which folds each EC includes, and the number of times that fold is used
		 */
		Set<String> labels = new LinkedHashSet<String>(); // these are the parts of the ECs we care about
		Map<String,Integer> nSymmDomainsByEc = new HashMap<String,Integer>();
		final Map<String,Map<String,Integer>> symmFoldsByEcs = new HashMap<String,Map<String,Integer>>();
		for (Map.Entry<String,String> entry : ecsBySymmDomain.entrySet()) {

			final String scopId = entry.getKey();
			final String ec = entry.getValue();

			String label = getLabel(ec, level);
			if (label == null) continue;

			// record the fold
			if (!symmFoldsByEcs.containsKey(label)) symmFoldsByEcs.put(label, new HashMap<String,Integer>());
			ScopDomain domain = scop.getDomainByScopID(scopId);
			ScopDescription desc = scop.getScopDescriptionBySunid(domain.getFoldId());
			String fold = desc.getClassificationId();
			StatUtils.plus(symmFoldsByEcs.get(label), fold);

			StatUtils.plus(nSymmDomainsByEc, label);
			labels.add(label);
		}

		/*
		 * build a map of the number of asymmetric domains by EC
		 * in this case we don't care about the folds
		 */
		Map<String,Integer> nAsymmDomainsByEc = new HashMap<String,Integer>();
		for (String ec : ecsByAsymmDomain.values()) {
			String label = getLabel(ec, level);
			if (label == null) continue;
			StatUtils.plus(nAsymmDomainsByEc, label);
			labels.add(label);
		}

		/*
		 * now print the results
		 */
		for (String label : labels) {

			// print basic stats: % symm domains and number of domains
			double fractionSymm = 0, fractionAsymm = 0;
			if (nSymmDomainsByEc.containsKey(label)) fractionSymm = (double) nSymmDomainsByEc.get(label);
			if (nAsymmDomainsByEc.containsKey(label)) fractionAsymm = (double) nAsymmDomainsByEc.get(label);
			System.out.print(label + "\t" + StatUtils.formatP(fractionSymm / (fractionSymm+fractionAsymm)) + "\t" + (fractionSymm+fractionAsymm));

			/*
			 * now we want to list example domains
			 * for this, we want the top most common folds
			 * so we need a new map sorted by values
			 */
			if (fractionSymm > 0) {
				final Map<String,Integer> domainCountByFold = symmFoldsByEcs.get(label);
				System.out.print("\t" + domainCountByFold.size()); // print out the number of folds

				if (domainCountByFold != null) {
					Comparator<String> nDomainsInFoldComp = new Comparator<String>() {
						@Override
						public int compare(String o1, String o2) {
							if (!domainCountByFold.containsKey(o1) || !domainCountByFold.containsKey(o2)) return 0;
							return domainCountByFold.get(o2).compareTo(domainCountByFold.get(o1));
						}
					};
					SortedMap<String,Integer> sortedFoldExamples = new TreeMap<String,Integer>(nDomainsInFoldComp);
					sortedFoldExamples.putAll(domainCountByFold);

					// now we have some examples, so we can print them out
					int i = 0;
					for (Map.Entry<String,Integer> entry : sortedFoldExamples.entrySet()) {
						String percentageOfEc = StatUtils.formatP(((double) entry.getValue()) / nSymmDomainsByEc.get(label));
						System.out.print("\t" + entry.getKey() + "(" + percentageOfEc + ")");
						i++;
						if (i > maxExamples) break;
					}

				}
			}
			System.out.println(); // we're done with this EC
		}
	}

	/**
	 * 
	 * @param ec
	 * @param level
	 * @return
	 */
	private String getLabel(String ec, int level) {
		String[] parts = ec.split("\\.");
		String label = parts[0];
		for (int i = 1; i < level; i++) {
			// this can happen if the EC number isn't fully specified (in fact, this is common)
			if (i >= parts.length) return null;
			label += "." + parts[i];
		}
		return label;
	}

}
