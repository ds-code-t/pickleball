Feature: debug2 text3

  Scenario: prec test 7
    * DDDqq '<appr>' and '<appr.name>'
    * DDDqq '<configs.driverconfigs>' and '<configs.appr.name>'


   @FFFF
  Scenario: prec test 6
    * DDDqq 'a' and '11'
    * DDDqq 'a' and '222'
    * go to: &aa
#    * go to previous: &aa
    * DDDqq 'a' and '333'
    * DDDqq 'a' and '444'
    @BOOKMARKS: | &aa |
    * DDDqq 'a' and '555'
    * DDDqq 'a' and '666'


  Scenario: tesa
    * DDDqq 'sss' and 'vvvv'
