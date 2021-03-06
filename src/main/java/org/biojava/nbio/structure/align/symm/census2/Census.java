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
package org.biojava.nbio.structure.align.symm.census2;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.biojava.nbio.core.util.ConcurrencyTools;
import org.biojava.nbio.structure.Atom;
import org.biojava.nbio.structure.StructureException;
import org.biojava.nbio.structure.align.StructureAlignment;
import org.biojava.nbio.structure.align.symm.CeSymm;
import org.biojava.nbio.structure.align.symm.order.OrderDetector;
import org.biojava.nbio.structure.align.symm.order.SequenceFunctionOrderDetector;
import org.biojava.nbio.structure.align.util.AtomCache;
import org.biojava.nbio.structure.scop.ScopCategory;
import org.biojava.nbio.structure.scop.ScopDatabase;
import org.biojava.nbio.structure.scop.ScopDescription;
import org.biojava.nbio.structure.scop.ScopDomain;
import org.biojava.nbio.structure.scop.ScopFactory;
import org.biojava.nbio.structure.utils.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the symmetry census on every domain. The work is done in order of superfamily (since we're using multiple cores,
 * this may not be the order in which they are output).
 *
 * @author dmyersturnbull
 * @deprecated
 */
@Deprecated
public class Census {

	private final static Logger logger = LoggerFactory.getLogger(Census.class);

	/**
	 * A class that creates a new {@link StructureAlignment StructureAlignments} for each {@link CensusJob}, to avoid
	 * concurrency issues.
	 * @author dmyersturnbull
	 *
	 */
	public static abstract class AlgorithmGiver {
		public static AlgorithmGiver getDefault() {
			return new AlgorithmGiver() {
				@Override
				public StructureAlignment getAlgorithm() {
					CeSymm ceSymm = new CeSymm();
					//					ConfigStrucAligParams params = ceSymm.getParameters();
					//					if (params instanceof CeParameters) {
					//						CeParameters ceparams = (CeParameters) params;
					//						ceparams.setScoringStrategy(CeParameters.SEQUENCE_CONSERVATION);
					//						ceparams.setSeqWeight(2);
					//						ceparams.setScoringStrategy(CeParameters.SIDE_CHAIN_SCORING);
					//						ceSymm.setParameters(ceparams);
					//					}
					return ceSymm;
				}
			};
		}

		public abstract StructureAlignment getAlgorithm();
	}

	private AtomCache cache;

	private boolean doPrefetch = false;

	private File file;
	private int numSymm;

	private int numTotal;
	
	private boolean recordAlignmentMapping = false;
	private boolean storeAfpChain = false;

	private int printFrequency = 400;
	private Map<String, Integer> symm = new TreeMap<String, Integer>();

	private Map<String, Integer> total = new TreeMap<String, Integer>();

	private AlgorithmGiver algorithm = null;

	private OrderDetector orderDetector = new SequenceFunctionOrderDetector();
	
	public void setRecordAlignmentMapping(boolean recordAlignmentMapping) {
		this.recordAlignmentMapping = recordAlignmentMapping;
	}

	public void setStoreAfpChain(boolean keepAfpChain) {
		this.storeAfpChain = keepAfpChain;
	}

	public void setOrderDetector(OrderDetector orderDetector) {
		this.orderDetector = orderDetector;
	}

	public static void buildDefault(File censusFile) {
		try {
			int maxThreads = Runtime.getRuntime().availableProcessors() - 1;
			Census census = new Census(maxThreads);
			census.setOutputWriter(censusFile);
			AtomCache cache = new AtomCache();
			cache.setFetchFileEvenIfObsolete(true);
			census.setCache(cache);
			census.run();
			System.out.println(census);
		} catch (RuntimeException e) {
			logger.warn(e.getMessage(), e);
		}
	}

	public static Significance getDefaultSignificance() {
		return SignificanceFactory.forCensus();
	}

	public static void main(String[] args) {
		if (args.length != 1) {
			System.err.println("Usage: " + Census.class.getSimpleName() + " output-census-file");
			return;
		}
		ScopFactory.setScopDatabase(ScopFactory.getSCOP(ScopFactory.VERSION_1_75A));
		final File censusFile = new File(args[0]);
		buildDefault(censusFile);
	}

	public Census() {
		this(Runtime.getRuntime().availableProcessors() - 1);
	}

	public Census(int maxThreads) {
		if (maxThreads < 1) maxThreads = 1;
		ConcurrencyTools.setThreadPoolSize(maxThreads);
	}

	public int getPrintFrequency() {
		return printFrequency;
	}

	public synchronized void print(Results census) {
		PrintWriter out = null;
		try {
			out = new PrintWriter(new BufferedWriter(new FileWriter(file)));
			String xml;
			xml = census.toXML();
			out.print(xml);
			out.flush();
			out.close();
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			if (out != null) out.close();
		}
	}

	public final void run() {

		Results census;
		
		try {

			if (file == null) throw new IllegalStateException("Must set file first");
			if (cache == null) cache = new AtomCache();

			ScopDatabase scop = ScopFactory.getSCOP();
			List<Future<Result>> futures = new ArrayList<Future<Result>>();
			census = getStartingResults();
			Significance significance = getSignificance();
			List<String> knownResults = getKnownResults(census);
			logger.info("There are " + knownResults.size() + " known results");

			int count = 0;
			List<ScopDomain> domains;
			if (doPrefetch) {
				logger.info("Performing prefetch...");
				domains = filterAndPrefetch();
				logger.info("Finished performing prefetch.");
			} else {
				domains = getDomains();
			}
			logger.info("There are " + domains.size() + " domains (" + knownResults.size() + " known)");

			List<CensusJob> submittedJobs = new ArrayList<CensusJob>(domains.size()); // to get time taken

			// submit jobs
			for (ScopDomain domain : domains) {
				if (domain.getRanges() == null || domain.getRanges().isEmpty()) {
					logger.warn("Skipping " + domain.getScopId() + " because SCOP ranges for it are not defined");
					continue;
				}
				if (knownResults.contains(domain.getScopId())) continue;
				if (count % 10 * (String.valueOf(domains.size()).length() - 1) == 0) {
					logger.info("Submitting " + count + " / " + domains.size());
				}
				logger.debug("Submitting new job for " + domain.getScopId() + " (job #" + count + ")");
				CensusJob calc = CensusJob.setUpJob(domain.getScopId(), count, getAlgorithm(), significance, cache, scop);
				calc.setRecordAlignmentMapping(recordAlignmentMapping);
				calc.setStoreAfpChain(storeAfpChain);
				calc.setOrderDetector(orderDetector);
				initializeJob(calc);
				submittedJobs.add(calc);
				Future<Result> result = ConcurrencyTools.submit(calc);
				futures.add(result);
				count++;
			}

			// wait for job returns and print
			for (Future<Result> future : futures) {
				Result result = null;
				try {
					logger.debug("Waiting for a job to finish");
					boolean flag = false;
					// We should do this in case the job gets interrupted
					// Sometimes the OS or JVM might do this
					// Use the flag instead of future == null because future.get() may actually return null
					while (!flag) {
						try {
							result = future.get();
							flag = true;
						} catch (InterruptedException e) {
							logger.debug("The calling thread was interrupted"); // probably not a concern
						}
					}
				} catch (ExecutionException e) {
					logger.error("Error on result (" + futures.size() + " remain)", e);
					continue;
				}
				logger.debug("Result was returned for " + census.size() + " / " + domains.size());
				logger.debug(result.toString());
				census.add(result);
				updateStats(result);
				if (census.size() % printFrequency == 0) {
					logger.debug("Printing to stream ");
					setTimeTaken(census, submittedJobs);
					print(census);
				}
			}
			logger.debug("Printing leftover results to stream");
			setTimeTaken(census, submittedJobs);
			print(census); // should be redundant
			logger.info("Finished!");

		} finally {
			ConcurrencyTools.shutdownAndAwaitTermination();
		}
		print(census);
	}

	private void setTimeTaken(Results census, List<CensusJob> submittedJobs) {
		long timeTaken = 0;
		int nSuccess = 0;
		for (CensusJob job : submittedJobs) {
			if (job.getTimeTaken() != null) {
				timeTaken += job.getTimeTaken();
				nSuccess++;
			}
		}
		avgTimeTaken = (double) timeTaken / (double) nSuccess;
		census.setMeanSecondsTaken(avgTimeTaken);
	}
	
	/**
	 * Do anything else to the {@link CensusJob} object before it is run.
	 * @param calc
	 */
	protected void initializeJob(CensusJob job) {
	}

	private double avgTimeTaken;

	public double getAvgTimeTaken() {
		return avgTimeTaken;
	}

	public void setCache(AtomCache cache) {
		this.cache = cache;
	}

	public void setOutputWriter(File out) {
		file = out;
	}

	public void setPrintFrequency(int printFrequency) {
		this.printFrequency = printFrequency;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		DecimalFormat df = new DecimalFormat();
		df.setMaximumFractionDigits(3);
		String newline = System.getProperty("line.separator");
		sb.append("time taken: " + avgTimeTaken + newline);
		double totalPercent = (double) numSymm / (double) numTotal * 100.0;
		sb.append("overall" + "\t" + df.format(totalPercent) + "%" + newline);
		for (Map.Entry<String, Integer> entry : total.entrySet()) {
			Integer nSymm = symm.get(entry.getKey());
			if (nSymm == null) nSymm = 0;
			double percent = (double) nSymm / (double) entry.getValue() * 100.0;
			sb.append(entry.getKey() + "\t" + df.format(percent) + "%" + newline);
		}
		return sb.toString();
	}

	/**
	 * Returns the names of the domains that we already analyzed.
	 *
	 * @param census
	 * @return
	 */
	private final List<String> getKnownResults(Results census) {
		List<String> names = new ArrayList<String>();
		List<Result> results = census.getData();
		int i = 0;
		for (Result result : results) {
			if (result == null) {
				logger.warn("A previous result (#" + i + ") was null.");
				continue;
			}
			names.add(result.getScopId());
			i++;
		}
		return names;
	}

	protected List<ScopDomain> filterAndPrefetch() {
		List<ScopDomain> domains = getDomains();
		List<ScopDomain> filtered = new ArrayList<ScopDomain>(domains.size());
		for (ScopDomain domain : domains) {
			try {
				Atom[] atoms = cache.getAtoms(domain.getScopId());
				if (atoms == null || atoms.length == 0) throw new StructureException("No atoms in array.");
				filtered.add(domain);
			} catch (IOException e) {
				logger.error("Could not preload structure for " + domain.getScopId(), e);
			} catch (StructureException e) {
				logger.error("Could not preload structure for " + domain.getScopId(), e);
			}
		}
		return filtered;
	}

	public AlgorithmGiver getAlgorithm() {
		if( this.algorithm == null) {
			this.algorithm = AlgorithmGiver.getDefault();
		}
		return this.algorithm;
	}
	public void setAlgorithm(AlgorithmGiver alg) {
		this.algorithm = alg;
	}

	protected List<ScopDomain> getDomains() {
		List<ScopDomain> domains = new ArrayList<ScopDomain>();
		ScopDatabase scop = ScopFactory.getSCOP();
		List<ScopDescription> superfamilies = scop.getByCategory(ScopCategory.Superfamily);
		for (ScopDescription superfamily : superfamilies) {
			domains.addAll(scop.getScopDomainsBySunid(superfamily.getSunID()));
		}
		return domains;
	}

	protected final Results getResultsFromPrevRun() {
		if (file.exists() && file.length() > 0) {
			try {
				Results results = Results.fromXML(file);
				logger.info("Found " + results.size() + " previous results from " + file.getPath());
				return results;
			} catch (IOException e) {
				final Date date = new Date();
				try {
					logger.warn("Could not load file " + file.getPath() + ". Starting from scratch.", e);
					FileUtils.copy(file, new File(file.getPath() + " __backup " + date));
					file.delete();
				} catch (IOException e1) {
					throw new RuntimeException("Could not read census file, and could not backup previous file", e1);
				}
			}
		}
		return null;
	}

	protected Significance getSignificance() {
		return getDefaultSignificance();
	}

	protected Results getStartingResults() {
		Results prevResults = getResultsFromPrevRun();
		if (prevResults != null) return prevResults;
		logger.info("Found no previous results");
		return new Results();
	}

	protected final void plus(Map<String, Integer> map, String key) {
		if (!map.containsKey(key)) map.put(key, 0);
		map.put(key, map.get(key) + 1);
	}

	protected void setDoPrefetch(boolean doPrefetch) {
		this.doPrefetch = doPrefetch;
	}

	protected void updateStats(Result result) {
		try {
			String[] parts = result.getClassification().split("\\.");
			plus(total, parts[0]);
			plus(total, parts[0] + "." + parts[1]);
			if (result.getIsSignificant()) {
				plus(symm, parts[0]);
				plus(symm, parts[0] + "." + parts[1]);
				numSymm++;
			}
			numTotal++;
		} catch (RuntimeException e) {
			logger.error("An error occurred updating the statistics for a result");
		}
	}

}
