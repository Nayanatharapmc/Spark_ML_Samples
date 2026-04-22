// Simple vanilla JS UI for lyrics classification.
// Uses backend API contract as-is:
// POST /lyrics/predict
// Content-Type: application/json
// Body: raw JSON string via JSON.stringify(lyricsText)

const lyricsInput = document.getElementById('lyricsInput');
const classifyBtn = document.getElementById('classifyBtn');
const clearBtn = document.getElementById('clearBtn');
const loadingEl = document.getElementById('loading');
const errorEl = document.getElementById('error');
const resultSection = document.getElementById('resultSection');
const predictedGenreEl = document.getElementById('predictedGenre');
const probabilityListEl = document.getElementById('probabilityList');

let chartInstance = null;

function setLoading(isLoading) {
  loadingEl.classList.toggle('hidden', !isLoading);
  classifyBtn.disabled = isLoading;
}

function showError(message) {
  errorEl.textContent = message;
  errorEl.classList.remove('hidden');
}

function clearError() {
  errorEl.textContent = '';
  errorEl.classList.add('hidden');
}

function clearResults() {
  resultSection.classList.add('hidden');
  predictedGenreEl.textContent = '-';
  probabilityListEl.innerHTML = '';

  if (chartInstance) {
    chartInstance.destroy();
    chartInstance = null;
  }
}

function renderProbabilityList(sortedEntries) {
  probabilityListEl.innerHTML = '';

  sortedEntries.forEach(([genre, value]) => {
    const li = document.createElement('li');

    const genreSpan = document.createElement('span');
    genreSpan.textContent = genre;

    const valueSpan = document.createElement('span');
    valueSpan.textContent = Number(value).toFixed(6);

    li.appendChild(genreSpan);
    li.appendChild(valueSpan);
    probabilityListEl.appendChild(li);
  });
}

function renderChart(labels, values) {
  const ctx = document.getElementById('probabilityChart').getContext('2d');

  if (chartInstance) {
    chartInstance.destroy();
  }

  chartInstance = new Chart(ctx, {
    type: 'bar',
    data: {
      labels,
      datasets: [{
        label: 'Probability',
        data: values,
        backgroundColor: 'rgba(37, 99, 235, 0.75)',
        borderColor: 'rgba(29, 78, 216, 1)',
        borderWidth: 1
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: true,
      scales: {
        y: {
          beginAtZero: true,
          max: 1,
          title: {
            display: true,
            text: 'Probability'
          }
        },
        x: {
          title: {
            display: true,
            text: 'Genre'
          }
        }
      },
      plugins: {
        legend: {
          display: false
        }
      }
    }
  });
}

async function classifyLyrics() {
  clearError();

  const lyricsText = lyricsInput.value.trim();
  if (!lyricsText) {
    showError('Please paste some lyrics before classifying.');
    return;
  }

  setLoading(true);

  try {
    const response = await fetch('/lyrics/predict', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      // Important: send as raw JSON string as required
      body: JSON.stringify(lyricsText)
    });

    if (!response.ok) {
      throw new Error(`API request failed with status ${response.status}.`);
    }

    const data = await response.json();

    // Ignore legacy fields and use only the required response fields.
    const predictedGenre = data.predictedGenre;
    const probabilities = data.probabilities;

    if (!predictedGenre || !probabilities || typeof probabilities !== 'object') {
      throw new Error('Unexpected API response format.');
    }

    const entries = Object.entries(probabilities)
      .filter(([, value]) => typeof value === 'number' && !Number.isNaN(value));

    if (entries.length === 0) {
      throw new Error('No probability values were returned by the API.');
    }

    // Sort descending for ranked table/list
    const sortedEntries = entries.sort((a, b) => b[1] - a[1]);

    predictedGenreEl.textContent = predictedGenre;
    renderProbabilityList(sortedEntries);

    const labels = sortedEntries.map(([genre]) => genre);
    const values = sortedEntries.map(([, value]) => value);
    renderChart(labels, values);

    resultSection.classList.remove('hidden');
  } catch (err) {
    clearResults();
    showError(err.message || 'Something went wrong while classifying lyrics.');
  } finally {
    setLoading(false);
  }
}

classifyBtn.addEventListener('click', classifyLyrics);

clearBtn.addEventListener('click', () => {
  lyricsInput.value = '';
  clearError();
  clearResults();
  lyricsInput.focus();
});
