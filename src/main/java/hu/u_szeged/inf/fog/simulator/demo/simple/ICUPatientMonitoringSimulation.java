package hu.u_szeged.inf.fog.simulator.demo.simple;

import hu.mta.sztaki.lpds.cloud.simulator.Timed;
import hu.mta.sztaki.lpds.cloud.simulator.energy.powermodelling.PowerState;
import hu.mta.sztaki.lpds.cloud.simulator.iaas.PhysicalMachine;
import hu.mta.sztaki.lpds.cloud.simulator.iaas.constraints.AlterableResourceConstraints;
import hu.mta.sztaki.lpds.cloud.simulator.io.Repository;
import hu.mta.sztaki.lpds.cloud.simulator.io.VirtualAppliance;
import hu.mta.sztaki.lpds.cloud.simulator.util.PowerTransitionGenerator;
import hu.u_szeged.inf.fog.simulator.application.Application;
import hu.u_szeged.inf.fog.simulator.application.strategy.RuntimeAwareApplicationStrategy;
import hu.u_szeged.inf.fog.simulator.demo.ScenarioBase;
import hu.u_szeged.inf.fog.simulator.iot.Device;
import hu.u_szeged.inf.fog.simulator.iot.EdgeDevice;
import hu.u_szeged.inf.fog.simulator.iot.mobility.GeoLocation;
import hu.u_szeged.inf.fog.simulator.iot.mobility.StaticMobilityStrategy;
import hu.u_szeged.inf.fog.simulator.iot.strategy.RandomDeviceStrategy;
import hu.u_szeged.inf.fog.simulator.node.ComputingAppliance;
import hu.u_szeged.inf.fog.simulator.provider.Instance;
import hu.u_szeged.inf.fog.simulator.util.EnergyDataCollector;
import hu.u_szeged.inf.fog.simulator.util.MapVisualiser;
import hu.u_szeged.inf.fog.simulator.util.SimLogger;
import hu.u_szeged.inf.fog.simulator.util.TimelineVisualiser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

public class ICUPatientMonitoringSimulation {
    private final static int PATIENTS_AMOUNT = 3;

    public static void main(String[] args) throws IOException {
        SimLogger.setLogging(1, true);

        String cloudfile = ScenarioBase.resourcePath + "healthcare_cloud_config.xml";

        VirtualAppliance va = new VirtualAppliance("healthcare-va", 100, 0, false, 2_147_483_648L); // 2GB storage

        AlterableResourceConstraints lightProcessing = new AlterableResourceConstraints(1, 0.001, 1_073_741_824L); // 1GB RAM for sensors
        AlterableResourceConstraints mediumProcessing = new AlterableResourceConstraints(2, 0.001, 2_147_483_648L); // 2GB RAM for fog processing
        AlterableResourceConstraints heavyProcessing = new AlterableResourceConstraints(4, 0.001, 4_294_967_296L); // 4GB RAM for cloud analytics

        ComputingAppliance hospitalCloud = new ComputingAppliance(cloudfile, "hospital-server",
                new GeoLocation(40.7128, -74.0060), 100); // New York Hospital
        ComputingAppliance icuFogServer = new ComputingAppliance(cloudfile, "icu-fog-server",
                new GeoLocation(40.7130, -74.0058), 80); // ICU fog node

        new EnergyDataCollector("hospital-server", hospitalCloud.iaas, true);
        new EnergyDataCollector("icu-fog-server", icuFogServer.iaas, true);

        icuFogServer.setParent(hospitalCloud, 5); // 5ms latency

        Instance lightInstance = new Instance("light-instance", va, lightProcessing, 0.01 / 60 / 60 / 1000);
        Instance mediumInstance = new Instance("medium-instance", va, mediumProcessing, 0.05 / 60 / 60 / 1000);
        Instance heavyInstance = new Instance("heavy-instance", va, heavyProcessing, 0.1 / 60 / 60 / 1000);

        Application patientMonitoring = new Application("monitoring-app",
                30 * 1000, // 30 second cycle
                500, // 500 bytes data per cycle
                5000, // 5000 instructions per task with max size
                true, // Can migrate
                new RuntimeAwareApplicationStrategy(0.9, 1.5), mediumInstance);

        Application temperatureMonitoring = new Application("temperature-app",
                60 * 1000, // 60 second cycle
                300, // 300 bytes data per cycle
                4000, // 4000 instructions per task with max size
                true, // Can migrate
                new RuntimeAwareApplicationStrategy(0.9, 1.5), lightInstance);

        Application dataStorage = new Application("storage-app",
                5 * 60 * 1000, // 5 minute cycle
                10000, // 10KB data per cycle
                50000, // 5000 instructions per task with max size
                true, // Can migrate
                new RuntimeAwareApplicationStrategy(0.8, 2.0), heavyInstance);

        hospitalCloud.addApplication(dataStorage);
        icuFogServer.addApplication(patientMonitoring);
        icuFogServer.addApplication(temperatureMonitoring);

        ArrayList<Device> devices = new ArrayList<>();

        for (int patientId = 1; patientId <= PATIENTS_AMOUNT; patientId++) {
            HashMap<String, Integer> latencyMap = new HashMap<>();
            latencyMap.put("icu-repo", 2); // 2ms to ICU fog server
            latencyMap.put("hospital-repo", 7); // 7ms to hospital cloud (5ms + 2ms)

            EnumMap<PowerTransitionGenerator.PowerStateKind, Map<String, PowerState>> transitions =
                    PowerTransitionGenerator.generateTransitions(0.05, 1.0, 1.5, 1, 2);

            final Map<String, PowerState> cpuTransitions = transitions.get(PowerTransitionGenerator.PowerStateKind.host);
            final Map<String, PowerState> stTransitions = transitions.get(PowerTransitionGenerator.PowerStateKind.storage);
            final Map<String, PowerState> nwTransitions = transitions.get(PowerTransitionGenerator.PowerStateKind.network);

            Repository patientRepo = new Repository(
                    512_000_000L, // 512 MB
                    "patient-repo-" + patientId,
                    1000,
                    1000,
                    1000,
                    latencyMap,
                    stTransitions,
                    nwTransitions
            ); // 512MB storage

            PhysicalMachine bedsideComputer = new PhysicalMachine(
                    1,
                    0.001,
                    1_073_741_824L, // 1 GB
                    patientRepo,
                    0,
                    0,
                    cpuTransitions
            );

            GeoLocation bedLocation = new GeoLocation(40.7131 + (patientId * 0.0001), -74.0057 + (patientId * 0.0001));

            Device patientMonitor = new EdgeDevice(
                    0, // Start immediately
                    8 * 60 * 60 * 1000, // 8 hours operation
                    100, // Full battery
                    30 * 1000, // 30 seconds sensing frequency
                    new StaticMobilityStrategy(bedLocation), // Stationary
                    new RandomDeviceStrategy(), // Random data
                    bedsideComputer,
                    0.1, // 10% processing load
                    20, // 20ms latency tolerance
                    true // Can be turned off
            );

            devices.add(patientMonitor);
            System.out.println("Created monitoring setup for Patient " + patientId);
        }

        System.out.println("\nStarting Healthcare Simulation...");
        System.out.println("Monitoring " + devices.size() + " patients");
        System.out.println("Applications: " +
                icuFogServer.applications.size() + " (fog) + " +
                hospitalCloud.applications.size() + " (cloud)");

        long startTime = System.nanoTime();
        Timed.simulateUntilLastEvent();
        long endTime = System.nanoTime();

        ScenarioBase.calculateIoTCost();
        ScenarioBase.logBatchProcessing(endTime - startTime);
        TimelineVisualiser.generateTimeline(ScenarioBase.resultDirectory);
        MapVisualiser.mapGenerator(ScenarioBase.scriptPath, ScenarioBase.resultDirectory, devices);
        EnergyDataCollector.writeToFile(ScenarioBase.resultDirectory);

        System.out.println("\nSimulation completed successfully!");
        System.out.println("Total simulation time: " + (endTime - startTime) / 1_000_000 + " ms");
        System.out.println("Results saved to: " + ScenarioBase.resultDirectory);
    }
}
