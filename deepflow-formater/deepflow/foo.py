def parse_file(lines):
    parentMethodStack = []
    methodId = 0
    for line in lines:
        # If the line represents the start of a block
        if line == "MS":
            methodId += 1
            parentMethodId = parentMethodStack[-1] if parentMethodStack else None
            print(f'Method ID: {methodId}, Parent Method ID: {parentMethodId}')
            parentMethodStack.append(methodId)
        # If the line represents the end of a block
        elif line == "ME":
            if parentMethodStack:
                parentMethodStack.pop()


# This is a sample file content
sample_lines = ["MS", "MS", "ME", "MS", "MS", "ME", "ME", "ME", "MS", "ME"]

parse_file(sample_lines)