name: Feature Request
description: Request a new feature
title: "[FEAT]: "
labels: ["enhancement"]
assignees:
  - heubeck
  - johannesmarx
  - beiertu-mms
body:
  - type: markdown
    attributes:
      value: |
        ### Thank you for taking your time to fill out this form!
        Please also search the [issues](https://github.com/MediaMarktSaturn/technolinator/issues) first before submitting a new one.

  - type: textarea
    id: expected-behavior
    attributes:
      label: Expected Behavior
      description: |
        Tell us what should be improved/added/changed/removed.
    validations:
      required: true

  - type: textarea
    id: actual-behavior
    attributes:
      label: Actual Behavior
      description: |
        Tell us the current behavior.
    validations:
      required: true

  - type: textarea
    id: additional-info
    attributes:
      label: Additional Information
      description: |
        Include here any additional information, which you think are relevant to this request.
    validations:
      required: false

