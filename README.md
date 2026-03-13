# Deepflow

## Introduction

**Deepflow** is a powerful Java tracing tool designed to help developers monitor and analyze the behavior of their applications in detail. Inspired by Btrace, Deepflow goes beyond simple function call recording by capturing argument values, object IDs, and detecting changes in argument values after function calls. This allows for in-depth tracing of instances, including tracking assignments of instances with the same or different content.

Deepflow consists of two main components:
1. **Java Agent**: Collects data during application execution.
2. **Python Analysis Tool**: Processes and analyzes the collected data using advanced machine learning techniques.

## Features

- **Function Call Recording**: Tracks method entry and exit points, along with argument values and object IDs.
- **Argument Change Detection**: Monitors changes in arguments after function calls.
- **Instance Tracing**: Detects modifications and assignments of instances within the application.
- **Flexible Output**: Outputs data to files, databases, or other specified destinations.
- **Comprehensive Analysis**: Python-based tool for detailed analysis of collected data.
- **Machine Learning Analysis**: Leverages machine learning algorithms to uncover patterns, detect anomalies, and provide predictive insights.

## Getting Started

### Prerequisites

- Java Development Kit (JDK) 17. Java 11 should work as well. Not tested
- Python 3.6 or higher

### Project structure description

The project is split into two parts:

- Java agent: Responsible for collecting the data from the running java application
- Formatter: Responsible for formating the collected raw data to format suitablemfor further analysis


### Installation

1. **Clone the Repository**
   ```sh
   git clone https://github.com/gabert/deepflow.git
   cd deepflow
   ```

2. **Build the agent and demo**
```sh
cd deepflow-agent
mvn clean install
```

### Running the Demo

The demo is a simple standalone Java application that creates and mutates a few objects. Run it with the agent attached:

```sh
java -javaagent:deepflow-agent/agent/target/deepflow-agent-jar-with-dependencies.jar=config=deepagent.cfg \
     -jar deepflow-agent/demo/target/DeepFlowDemo-0.0.1-SNAPSHOT.jar
```

Trace files will be written to the location specified in `deepagent.cfg` (`session_dump_location`, default `D:\temp`).

### Configuration (`deepagent.cfg`)

Key properties:

```properties
session_dump_location=D:\temp          # Where trace files are written
matchers_include=com\.github\..*       # Regex of classes to instrument
destination=file                       # file | zip | kafka
compress_file_output=false
```

# ToDo:
- write tutorial
- write argument checking at the end of the call.