package de.envisia.sbt.angular

import sbt.FeedbackProvidedException

class Angular2Exception(message: String) extends RuntimeException(message) with FeedbackProvidedException
