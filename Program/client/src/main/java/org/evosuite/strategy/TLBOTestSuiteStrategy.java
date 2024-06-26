package org.evosuite.strategy;

import org.evosuite.Properties;
import org.evosuite.coverage.TestFitnessFactory;
import org.evosuite.coverage.branch.BranchCoverageSuiteFitness;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.ga.TLBOAlgorithmV2;
import org.evosuite.ga.stoppingconditions.MaxStatementsStoppingCondition;
import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.ClientState;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionTracer;
import org.evosuite.testcase.factories.RandomLengthTestFactory;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;
import org.evosuite.testsuite.factories.TestSuiteChromosomeFactory;
import org.evosuite.utils.ArrayUtil;
import org.evosuite.utils.LoggingUtils;
import org.evosuite.utils.Randomness;

import java.util.ArrayList;
import java.util.List;

public class TLBOTestSuiteStrategy extends TestGenerationStrategy{
    @Override
    public TestSuiteChromosome generateTests() {
        // Set up search algorithm
        LoggingUtils.getEvoLogger().info("* Setting up search algorithm for whole suite generation");
//        PropertiesSuiteGAFactory algorithmFactory = new PropertiesSuiteGAFactory();
//        GeneticAlgorithm<TestSuiteChromosome> algorithm = algorithmFactory.getSearchAlgorithm();
        TLBOAlgorithmV2 algorithm = new TLBOAlgorithmV2(new TestSuiteChromosomeFactory(new RandomLengthTestFactory()));

//        if (Properties.SERIALIZE_GA || Properties.CLIENT_ON_THREAD)
//            TestGenerationResultBuilder.getInstance().setGeneticAlgorithm(algorithm);

        long startTime = System.currentTimeMillis() / 1000;

        // What's the search target
        List<TestSuiteFitnessFunction> fitnessFunctions = new ArrayList<>();
        fitnessFunctions.add(new BranchCoverageSuiteFitness());

        algorithm.setFitnessFunctions(fitnessFunctions);
//		for(TestSuiteFitnessFunction f : fitnessFunctions)
//			algorithm.addFitnessFunction(f);

        // if (Properties.SHOW_PROGRESS && !logger.isInfoEnabled())
//        algorithm.addListener(progressMonitor); // FIXME progressMonitor may cause
        // client hang if EvoSuite is
        // executed with -prefix!

//        if (Properties.TRACK_DIVERSITY)
//            algorithm.addListener(new DiversityObserver());

        if (ArrayUtil.contains(Properties.CRITERION, Properties.Criterion.DEFUSE)
                || ArrayUtil.contains(Properties.CRITERION, Properties.Criterion.ALLDEFS)
                || ArrayUtil.contains(Properties.CRITERION, Properties.Criterion.STATEMENT)
                || ArrayUtil.contains(Properties.CRITERION, Properties.Criterion.RHO)
                || ArrayUtil.contains(Properties.CRITERION, Properties.Criterion.AMBIGUITY))
            ExecutionTracer.enableTraceCalls();

        // TODO: why it was only if "analyzing"???
        // if (analyzing)
//        algorithm.resetStoppingConditions();

        List<TestFitnessFunction> goals = getGoals(true);
        if (!canGenerateTestsForSUT()) {
            LoggingUtils.getEvoLogger().info("* Found no testable methods in the target class "
                    + Properties.TARGET_CLASS);
            ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Total_Goals, goals.size());

            return new TestSuiteChromosome();
        }

        /*
         * Proceed with search if CRITERION=EXCEPTION, even if goals is empty
         */
        TestSuiteChromosome testSuite = null;
        if (!(Properties.STOP_ZERO && goals.isEmpty()) || ArrayUtil.contains(Properties.CRITERION, Properties.Criterion.EXCEPTION)) {
            // Perform search
            LoggingUtils.getEvoLogger().info("* Using seed {}", Randomness.getSeed());
            LoggingUtils.getEvoLogger().info("* Starting evolution");
            ClientServices.getInstance().getClientNode().changeState(ClientState.SEARCH);

            algorithm.generateSolution();
            // TODO: Refactor MOO!
            // bestSuites = (List<TestSuiteChromosome>) ga.getBestIndividuals();
            testSuite = (TestSuiteChromosome) algorithm.getBestIndividual();
        } else {
            zeroFitness.setFinished();
            testSuite = new TestSuiteChromosome();
            for (FitnessFunction<TestSuiteChromosome> ff : fitnessFunctions) {
                testSuite.setCoverage(ff, 1.0);
            }
        }

        long endTime = System.currentTimeMillis() / 1000;

        goals = getGoals(false); //recalculated now after the search, eg to handle exception fitness
        ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Total_Goals, goals.size());

        // Newline after progress bar
        if (Properties.SHOW_PROGRESS)
            LoggingUtils.getEvoLogger().info("");

        if (!Properties.IS_RUNNING_A_SYSTEM_TEST) { //avoid printing time related info in system tests due to lack of determinism
            LoggingUtils.getEvoLogger().info("* Search finished after "
                    + (endTime - startTime)
                    + "s and "
                    + algorithm.getAge()
                    + " generations, "
                    + MaxStatementsStoppingCondition.getNumExecutedStatements()
                    + " statements, best individual has fitness: "
                    + testSuite.getFitness());
        }

        // Search is finished, send statistics
        sendExecutionStatistics();

        return testSuite;
    }

    private List<TestFitnessFunction> getGoals(boolean verbose) {
        List<TestFitnessFactory<? extends TestFitnessFunction>> goalFactories = getFitnessFactories();
        List<TestFitnessFunction> goals = new ArrayList<>();

        if (goalFactories.size() == 1) {
            TestFitnessFactory<? extends TestFitnessFunction> factory = goalFactories.iterator().next();
            goals.addAll(factory.getCoverageGoals());

            if (verbose) {
                LoggingUtils.getEvoLogger().info("* Total number of test goals: {}", factory.getCoverageGoals().size());
                if (Properties.PRINT_GOALS) {
                    for (TestFitnessFunction goal : factory.getCoverageGoals())
                        LoggingUtils.getEvoLogger().info("" + goal.toString());
                }
            }
        } else {
            if (verbose) {
                LoggingUtils.getEvoLogger().info("* Total number of test goals: ");
            }

            for (TestFitnessFactory<? extends TestFitnessFunction> goalFactory : goalFactories) {
                goals.addAll(goalFactory.getCoverageGoals());

                if (verbose) {
                    LoggingUtils.getEvoLogger().info("  - " + goalFactory.getClass().getSimpleName().replace("CoverageFactory", "")
                            + " " + goalFactory.getCoverageGoals().size());
                    if (Properties.PRINT_GOALS) {
                        for (TestFitnessFunction goal : goalFactory.getCoverageGoals())
                            LoggingUtils.getEvoLogger().info("" + goal.toString());
                    }
                }
            }
        }
        return goals;
    }

}
