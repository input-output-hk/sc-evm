package io.iohk.scevm.utils

object ValidationUtils {

  /** This function combines multiple validations on object.
    *
    * @param obj object to return if all validations pass .
    * @param eithers list of required validations.
    * @return object if all validations pass, else non-empty set of errors.
    */
  def combineValidations[A, B](validations: B => Either[A, _]*)(obj: B): Either[Set[A], B] = {
    val errors = validations.map(_(obj)).collect { case Left(e) => e }
    if (errors.isEmpty) Right(obj) else Left(errors.toSet)
  }
}
