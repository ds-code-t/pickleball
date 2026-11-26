defineStep(/I count (\d+) for the js step/, function (n) {
  saveValue("jsStepCount", n);
});
