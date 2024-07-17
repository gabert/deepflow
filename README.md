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

2. **Build Project**
```sh
cd agent
mvn clean install
```

java -javaagent:d:\Pracovny\Projekty\Projects\flowspy\agent\target\are-agent-jar-with-dependencies.jar=config=D:\temp\deepagent.cfg   


# ToDo:
- write tutorial
- write argument checking at the end of the call.