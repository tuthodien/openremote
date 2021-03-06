package org.openremote.test.assets

import org.openremote.agent.protocol.simulator.SimulatorProtocol
import org.openremote.manager.agent.AgentService
import org.openremote.manager.asset.AssetProcessingService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.datapoint.AssetDatapointService
import org.openremote.manager.setup.SetupService
import org.openremote.manager.setup.builtin.ManagerTestSetup
import org.openremote.model.attribute.AttributeRef
import org.openremote.model.datapoint.DatapointInterval
import org.openremote.model.value.Values
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.time.Instant
import java.util.concurrent.TimeUnit

import static java.util.concurrent.TimeUnit.DAYS
import static java.util.concurrent.TimeUnit.SECONDS
import static org.openremote.manager.datapoint.AssetDatapointService.DATA_POINTS_MAX_AGE_DAYS_DEFAULT
import static org.openremote.manager.setup.builtin.ManagerTestSetup.thingLightToggleAttributeName

class AssetDatapointTest extends Specification implements ManagerContainerTrait {

    def "Test number and toggle attribute storage, retrieval and purging"() {

        given: "expected conditions"
        def conditions = new PollingConditions(timeout: 10, delay: 0.2)

        when: "the demo agent and thing have been deployed"
        def datapointPurgeDays = DATA_POINTS_MAX_AGE_DAYS_DEFAULT
        def container = startContainer(defaultConfig(), defaultServices())
        def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
        def agentService = container.getService(AgentService.class)
        def assetStorageService = container.getService(AssetStorageService.class)
        def assetProcessingService = container.getService(AssetProcessingService.class)
        def assetDatapointService = container.getService(AssetDatapointService.class)

        and: "the clock is stopped for testing purposes"
        stopPseudoClock()

        then: "the simulator protocol instance should have been initialised and attributes linked"
        conditions.eventually {
            assert agentService.protocolInstanceMap.get(managerTestSetup.agentId) != null
            assert ((SimulatorProtocol) agentService.protocolInstanceMap.get(managerTestSetup.agentId)).linkedAttributes.size() == 4
            assert ((SimulatorProtocol) agentService.protocolInstanceMap.get(managerTestSetup.agentId)).linkedAttributes.get(new AttributeRef(managerTestSetup.thingId, "light1PowerConsumption")).getValueAs(Double.class).orElse(0d) == 12.345d
        }

        when: "an attribute linked to the simulator agent receives some values"
        def simulatorProtocol = ((SimulatorProtocol) agentService.protocolInstanceMap.get(managerTestSetup.agentId))
        advancePseudoClock(10, SECONDS, container)
        simulatorProtocol.updateSensor(new AttributeRef(managerTestSetup.thingId, "light1PowerConsumption"), 13.3d)
        advancePseudoClock(10, SECONDS, container)
        simulatorProtocol.updateSensor(new AttributeRef(managerTestSetup.thingId, "light1PowerConsumption"), null)
        advancePseudoClock(10, SECONDS, container)
        simulatorProtocol.updateSensor(new AttributeRef(managerTestSetup.thingId, "light1PowerConsumption"), 13.3d)

        and: "we move forward in time more than purge days and a simulated sensor receives a new value"
        advancePseudoClock(datapointPurgeDays, DAYS, container)
        def datapoint1ExpectedTimestamp = getClockTimeOf(container)
        simulatorProtocol.updateSensor(new AttributeRef(managerTestSetup.thingId, "light1PowerConsumption"), 13.3d)

        then: "the attribute should be updated"
        conditions.eventually {
            def thing = assetStorageService.find(managerTestSetup.thingId, true)
            assert thing.getAttribute("light1PowerConsumption").flatMap { it.getValueAs(Double.class) }.orElse(null) == 13.3d
        }

        when: "a simulated sensor receives a new value"
        advancePseudoClock(10, SECONDS, container)
        def datapoint2ExpectedTimestamp = getClockTimeOf(container)
        simulatorProtocol.updateSensor(new AttributeRef(managerTestSetup.thingId, "light1PowerConsumption"), 14.4d)

        then: "the attribute should be updated"
        conditions.eventually {
            def thing = assetStorageService.find(managerTestSetup.thingId, true)
            assert thing.getAttribute("light1PowerConsumption").flatMap { it.getValueAs(Double.class) }.orElse(null) == 14.4d
        }

        when: "a simulated sensor receives a new value"
        advancePseudoClock(10, SECONDS, container)
        def datapoint3ExpectedTimestamp = getClockTimeOf(container)
        simulatorProtocol.updateSensor(new AttributeRef(managerTestSetup.thingId, "light1PowerConsumption"), 15.5d)

        then: "the attribute should be updated"
        conditions.eventually {
            def thing = assetStorageService.find(managerTestSetup.thingId, true)
            assert thing.getAttribute("light1PowerConsumption").flatMap { it.getValueAs(Double.class) }.orElse(null) == 15.5d
        }

        when: "a simulated sensor receives a new value"
        advancePseudoClock(10, SECONDS, container)
        simulatorProtocol.updateSensor(new AttributeRef(managerTestSetup.thingId, "light1PowerConsumption"), null)

        then: "the attribute should be updated"
        conditions.eventually {
            def thing = assetStorageService.find(managerTestSetup.thingId, true)
            assert !thing.getAttribute("light1PowerConsumption").flatMap { it.getValue() }.isPresent()
        }

        expect: "the datapoints to be stored"
        conditions.eventually {
            def datapoints = assetDatapointService.getDatapoints(new AttributeRef(managerTestSetup.thingId, "light1PowerConsumption"))
            assert datapoints.size() == 5

            // Note that the "No value" sensor update should not have created a datapoint, the first
            // datapoint is the last sensor update with an actual value

            assert Values.getValue(datapoints.get(0).value, Double.class).orElse(null) == 15.5d
            assert datapoints.get(0).timestamp == datapoint3ExpectedTimestamp

            assert Values.getValue(datapoints.get(1).value, Double.class).orElse(null) == 14.4d
            assert datapoints.get(1).timestamp == datapoint2ExpectedTimestamp

            assert Values.getValue(datapoints.get(2).value, Double.class).orElse(null) == 13.3d
            assert datapoints.get(2).timestamp == datapoint1ExpectedTimestamp

            assert Values.getValue(datapoints.get(3).value, Double.class).orElse(null) == 13.3d

            assert Values.getValue(datapoints.get(4).value, Double.class).orElse(null) == 13.3d
        }

        and: "the aggregated datapoints should match"
        conditions.eventually {
            def thing = assetStorageService.find(managerTestSetup.thingId, true)
            def aggregatedDatapoints = assetDatapointService.getValueDatapoints(
                thing.getId(),
                thing.getAttribute("light1PowerConsumption").orElseThrow({ new RuntimeException("Missing attribute") }),
                DatapointInterval.MINUTE,
                getClockTimeOf(container),
                getClockTimeOf(container) + 3600000
            )
            assert aggregatedDatapoints.size() == 61
        }

        // ------------------------------------
        // Test boolean data point storage
        // ------------------------------------

        when: "a simulated boolean sensor receives a new value"
        advancePseudoClock(10, SECONDS, container)
        datapoint1ExpectedTimestamp = getClockTimeOf(container)
        simulatorProtocol.updateSensor(new AttributeRef(managerTestSetup.thingId, thingLightToggleAttributeName), false)

        then: "the attribute should be updated"
        conditions.eventually {
            def thing = assetStorageService.find(managerTestSetup.thingId, true)
            assert !thing.getAttribute(thingLightToggleAttributeName).flatMap { it.getValueAs(Boolean.class) }.orElse(null)
        }

        when: "a simulated sensor receives a new value"
        advancePseudoClock(10, SECONDS, container)
        datapoint2ExpectedTimestamp = getClockTimeOf(container)
        simulatorProtocol.updateSensor(new AttributeRef(managerTestSetup.thingId, thingLightToggleAttributeName), true)

        then: "the attribute should be updated"
        conditions.eventually {
            def thing = assetStorageService.find(managerTestSetup.thingId, true)
            assert thing.getAttribute(thingLightToggleAttributeName).flatMap { it.getValueAs(Boolean.class) }.orElse(null)
        }

        when: "a simulated sensor receives a new value"
        advancePseudoClock(10, SECONDS, container)
        datapoint3ExpectedTimestamp = getClockTimeOf(container)
        simulatorProtocol.updateSensor(new AttributeRef(managerTestSetup.thingId, thingLightToggleAttributeName), false)

        then: "the attribute should be updated"
        conditions.eventually {
            def thing = assetStorageService.find(managerTestSetup.thingId, true)
            assert !thing.getAttribute(thingLightToggleAttributeName).flatMap { it.getValueAs(Boolean.class) }.orElse(null)
        }

        expect: "the datapoints to be stored"
        conditions.eventually {
            def datapoints = assetDatapointService.getDatapoints(new AttributeRef(managerTestSetup.thingId, thingLightToggleAttributeName))
            assert datapoints.size() == 3

            assert !Values.getBoolean(datapoints.get(0).value).orElse(null)
            assert datapoints.get(0).timestamp == datapoint3ExpectedTimestamp

            assert Values.getBoolean(datapoints.get(1).value).orElse(null)
            assert datapoints.get(1).timestamp == datapoint2ExpectedTimestamp

            assert !Values.getBoolean(datapoints.get(2).value).orElse(null)
            assert datapoints.get(2).timestamp == datapoint1ExpectedTimestamp
        }

        and: "the aggregated datapoints should match"
        conditions.eventually {
            def thing = assetStorageService.find(managerTestSetup.thingId, true)
            def aggregatedDatapoints = assetDatapointService.getValueDatapoints(
                thing.getId(),
                thing.getAttribute(thingLightToggleAttributeName).orElseThrow({ new RuntimeException("Missing attribute") }),
                DatapointInterval.MINUTE,
                getClockTimeOf(container),
                getClockTimeOf(container) + 3600000
            )
            assert aggregatedDatapoints.size() == 61
        }

        // ------------------------------------
        // Test purging of data points
        // ------------------------------------

        when: "time moves forward by more than purge days"
        advancePseudoClock(datapointPurgeDays + 1, DAYS, container)

        and: "the power sensor with default max age receives a new value"
        def datapoint4ExpectedTimestamp = getClockTimeOf(container)
        simulatorProtocol.updateSensor(new AttributeRef(managerTestSetup.thingId, "light1PowerConsumption"), 17.5d)

        and: "the toggle sensor with a custom max age of 7 days receives a new value"
        simulatorProtocol.updateSensor(new AttributeRef(managerTestSetup.thingId, thingLightToggleAttributeName), true)

        then: "the datapoints should be stored"
        conditions.eventually {
            def powerDatapoints = assetDatapointService.getDatapoints(new AttributeRef(managerTestSetup.thingId, "light1PowerConsumption"))
            def toggleDatapoints = assetDatapointService.getDatapoints(new AttributeRef(managerTestSetup.thingId, thingLightToggleAttributeName))

            assert powerDatapoints.size() == 6
            assert Values.getValue(powerDatapoints.get(0).value, Double.class).orElse(null) == 17.5d
            assert powerDatapoints.get(0).timestamp == datapoint4ExpectedTimestamp

            assert toggleDatapoints.size() == 4
            assert Values.getBoolean(toggleDatapoints.get(0).value).orElse(false)
            assert toggleDatapoints.get(0).timestamp == datapoint4ExpectedTimestamp
        }

        when: "the clock advances to the next days purge routine execution time"
        advancePseudoClock(assetDatapointService.getFirstRunMillis(Instant.ofEpochMilli(getClockTimeOf(container))), TimeUnit.MILLISECONDS, container)

        and: "the purge routine runs"
        assetDatapointService.purgeDataPoints()

        then: "data points older than purge days should be purged for the power sensor"
        conditions.eventually {
            def datapoints = assetDatapointService.getDatapoints(new AttributeRef(managerTestSetup.thingId, "light1PowerConsumption"))
            assert datapoints.size() == 1
            assert Values.getValue(datapoints.get(0).value, Double.class).orElse(null) == 17.5d
            assert datapoints.get(0).timestamp == datapoint4ExpectedTimestamp
        }

        and: "no data points should have been purged for the toggle sensor"
        conditions.eventually {
            def datapoints = assetDatapointService.getDatapoints(new AttributeRef(managerTestSetup.thingId, thingLightToggleAttributeName))
            assert datapoints.size() == 4
            assert Values.getBoolean(datapoints.get(0).value).orElse(false)
            assert datapoints.get(0).timestamp == datapoint4ExpectedTimestamp
        }

        when: "the clock advances 3 times the purge duration"
        advancePseudoClock(3 * datapointPurgeDays, DAYS, container)

        and: "the purge routine runs"
        assetDatapointService.purgeDataPoints()

        then: "all data points should be purged for power sensor"
        conditions.eventually {
            def datapoints = assetDatapointService.getDatapoints(new AttributeRef(managerTestSetup.thingId, "light1PowerConsumption"))
            assert datapoints.isEmpty()
        }

        and: "no data points should have been purged for the toggle sensor"
        conditions.eventually {
            def datapoints = assetDatapointService.getDatapoints(new AttributeRef(managerTestSetup.thingId, thingLightToggleAttributeName))
            assert datapoints.size() == 4
            assert Values.getBoolean(datapoints.get(0).value).orElse(false)
            assert datapoints.get(0).timestamp == datapoint4ExpectedTimestamp
        }

        when: "the clock advances 3 times the purge duration"
        advancePseudoClock(3 * datapointPurgeDays, DAYS, container)

        and: "the purge routine runs"
        assetDatapointService.purgeDataPoints()

        then: "data points older than 7 days should have been purged for the toggle sensor"
        conditions.eventually {
            def datapoints = assetDatapointService.getDatapoints(new AttributeRef(managerTestSetup.thingId, thingLightToggleAttributeName))
            assert datapoints.size() == 1
            assert Values.getBoolean(datapoints.get(0).value).orElse(false)
            assert datapoints.get(0).timestamp == datapoint4ExpectedTimestamp
        }

        when: "the clock advances by the purge duration"
        advancePseudoClock(datapointPurgeDays, DAYS, container)

        and: "the purge routine runs"
        assetDatapointService.purgeDataPoints()

        then: "all data points should have been purged for the toggle sensor"
        conditions.eventually {
            def datapoints = assetDatapointService.getDatapoints(new AttributeRef(managerTestSetup.thingId, thingLightToggleAttributeName))
            assert datapoints.isEmpty()
        }
    }
}
