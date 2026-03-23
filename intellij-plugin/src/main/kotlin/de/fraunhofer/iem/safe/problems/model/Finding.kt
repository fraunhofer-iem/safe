package de.fraunhofer.iem.safe.problems.model

class Finding(

    var ruleId: String,
    var description: String,
    var lineNumber: Int,
    var columnNumber: Int
){
    init {
        require(ruleId.isNotBlank()) { "The rule should not be empty" }
    }

    override fun toString(): String {
        return "Finding(ruleId='$ruleId', " +
                "description='$description', " +
                "lineNumber=$lineNumber, " +
                "columnNumber=$columnNumber)"
    }
}