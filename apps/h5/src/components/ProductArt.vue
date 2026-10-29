<template>
  <svg viewBox="0 0 250 250" role="img" aria-label="商品瓶型示意图">
    <defs>
      <linearGradient id="bottle" x1="0" x2="1">
        <stop stop-color="#a06e36" />
        <stop offset=".45" stop-color="#c2975e" />
        <stop offset="1" stop-color="#90602d" />
      </linearGradient>
      <linearGradient id="cap" x1="0" x2="1">
        <stop stop-color="#2e4134" />
        <stop offset=".5" stop-color="#56735b" />
        <stop offset="1" stop-color="#304d38" />
      </linearGradient>
    </defs>
    <ellipse cx="128" cy="226" rx="48" ry="7" fill="#3a5032" opacity=".09" />
    <path d="M37 206Q60 106 90 79Q63 157 37 206" fill="#b1bda0" opacity=".6" />
    <path d="M42 200Q14 151 27 121Q43 170 42 200" fill="#c8d0b6" />
    <rect x="96" y="30" width="57" height="43" rx="9" fill="url(#cap)" />
    <rect x="100" y="69" width="49" height="9" rx="2" fill="#8b7948" />
    <path
      d="M99 78Q83 83 83 107V205Q83 218 98 218H151Q166 218 166 204V107Q166 83 149 78Z"
      fill="url(#bottle)"
    />
    <rect x="86" y="111" width="77" height="75" rx="2" fill="#ede7cc" />
    <text x="125" y="135" text-anchor="middle" font-family="serif" font-size="14" fill="#3e5943">
      初语山言
    </text>
    <text
      x="125"
      y="150"
      text-anchor="middle"
      font-family="sans-serif"
      font-size="5"
      letter-spacing="2"
      fill="#8f987b"
    >
      CHUYU SHANYAN
    </text>
    <path
      d="M110 170Q125 144 139 170M115 170Q129 157 141 175"
      fill="none"
      stroke="#9aab87"
      stroke-width=".8"
    />
    <text x="125" y="200" text-anchor="middle" font-size="6" fill="#e3d8b0">150mL</text>
  </svg>
</template>
